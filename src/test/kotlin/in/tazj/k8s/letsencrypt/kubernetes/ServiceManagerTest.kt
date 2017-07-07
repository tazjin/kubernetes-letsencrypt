package `in`.tazj.k8s.letsencrypt.kubernetes

import `in`.tazj.k8s.letsencrypt.acme.CertificateRequestHandler
import `in`.tazj.k8s.letsencrypt.model.CertificateRequest
import `in`.tazj.k8s.letsencrypt.model.EXPIRY_ANNOTATION
import `in`.tazj.k8s.letsencrypt.model.REQUEST_ANNOTATION
import `in`.tazj.k8s.letsencrypt.model.SECRET_NAME_ANNOTATION
import com.nhaarman.mockito_kotlin.any
import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockito_kotlin.eq
import com.nhaarman.mockito_kotlin.mock
import io.fabric8.kubernetes.api.model.ObjectMeta
import io.fabric8.kubernetes.api.model.Secret
import io.fabric8.kubernetes.api.model.Service
import org.funktionale.option.Option
import org.funktionale.option.toOption
import org.hamcrest.CoreMatchers.hasItems
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDate


class ServiceManagerTest {
    private val serviceManager: ServiceManager
    private val EXISTING_CERT = "existing-k8s-io-tls"
    private val RENEWAL_CERT = "renewal-k8s-io-tls"
    private val CUSTOM_NAME = "customSecretName"
    private val MISMATCH_SECRET = "mismatchSecret"
    private val REMOVED_CERT = "removed-k8s-io-tls"

    init {
        val secretManager = getMockedSecretManager()
        val requestHandler: CertificateRequestHandler = mock()

        serviceManager = ServiceManager("default", secretManager, requestHandler)
    }

    private fun getMockedSecretManager(): SecretManager {
        val existingSecret = prepareExistingSecret()
        val renewalSecret = prepareRenewalSecret()
        val mismatchSecret = prepareDomainMismatchSecret()
        val removedSecret = prepareDomainRemovedSecret()

        val secretManager: SecretManager = mock {
            on { getSecret(any(), any()) } doReturn (Option.None)
            on { getSecret(eq("default"), eq(EXISTING_CERT)) } doReturn (existingSecret)
            on { getSecret(eq("default"), eq(RENEWAL_CERT)) } doReturn (renewalSecret)
            on { getSecret(eq("default"), eq(MISMATCH_SECRET)) } doReturn (mismatchSecret)
            on { getSecret(eq("default"), eq(REMOVED_CERT)) } doReturn (removedSecret)
        }

        return secretManager
    }

    /** Prepare a plain, mocked secret for an existing certificate.  */
    private fun prepareExistingSecret(): Option<Secret> {
        val annotations = mapOf(REQUEST_ANNOTATION to "[\"existing.k8s.io\"]")
        val secretMeta: ObjectMeta = mock {
            on { name } doReturn (EXISTING_CERT)
            on { getAnnotations() } doReturn (annotations)
        }

        val secret: Secret = mock {
            on { metadata } doReturn (secretMeta)
        }

        return secret.toOption()
    }

    /** Prepare a mocked secret for an expired certificate.  */
    private fun prepareRenewalSecret(): Option<Secret> {
        val expiryDate = LocalDate.now().minusDays(1)
        val annotations = mapOf(EXPIRY_ANNOTATION to expiryDate.toString())

        val secretMeta: ObjectMeta = mock {
            on { name } doReturn (RENEWAL_CERT)
            on { getAnnotations() } doReturn (annotations)
        }

        val secret: Secret = mock {
            on { metadata } doReturn (secretMeta)
        }

        return secret.toOption()
    }

    /** Prepare a mocked secret with domain mismatch.  */
    private fun prepareDomainMismatchSecret(): Option<Secret> {
        val annotations = mapOf(
                REQUEST_ANNOTATION to "[\"test1.k8s.io\", \"test3.k8s.io\"]"
        )

        val secretMeta: ObjectMeta = mock {
            on { name } doReturn (MISMATCH_SECRET)
            on { getAnnotations() } doReturn (annotations)
        }

        val secret: Secret = mock {
            on { metadata } doReturn (secretMeta)
        }

        return secret.toOption()
    }

    /** Prepare a mocked secret with a domain that has been removed */
    private fun prepareDomainRemovedSecret(): Option<Secret> {
        val annotations = mapOf(
                REQUEST_ANNOTATION to "[\"test1.k8s.io\", \"removed.k8s.io\"]"
        )

        val secretMeta: ObjectMeta = mock {
            on { name } doReturn (REMOVED_CERT)
            on { getAnnotations() } doReturn (annotations)
        }

        val secret: Secret = mock {
            on { metadata } doReturn (secretMeta)
        }

        return secret.toOption()
    }

    private fun createTestRequest(annotations: Map<String, String>): Option<CertificateRequest> {
        val testMetadata: ObjectMeta = mock {
            on { getAnnotations() } doReturn (annotations)
            on { name } doReturn ("testService")
        }

        val testService: Service = mock {
            on { metadata } doReturn (testMetadata)
        }

        return serviceManager.prepareCertificateRequest(testService)
    }

    @Test
    fun testNormalCertificateRequest() {
        val testDomain = "test.k8s.io"
        val annotations = mapOf(REQUEST_ANNOTATION to testDomain)
        val request = createTestRequest(annotations)
        val expectedSecret = "test-k8s-io-tls"

        assertTrue("Service manager builds a request", request.isDefined())
        assertFalse("Request is not a renewal", request.get().renew)
        assertEquals("Request secret name matches", expectedSecret, request.get().secretName)
        assertEquals("One domain is included", 1, request.get().domains.size)
        assertEquals("Request domain name matches", testDomain, request.get().domains[0])
    }

    @Test
    fun testExistingCertificateRequest() {
        val testDomain = "existing.k8s.io"
        val annotations = mapOf(REQUEST_ANNOTATION to testDomain)
        val request = createTestRequest(annotations)

        assertFalse("No certificate is requested", request.isDefined())
    }

    @Test
    fun testCertificateRenewalRequest() {
        val testDomain = "renewal.k8s.io"
        val annotations = mapOf(REQUEST_ANNOTATION to testDomain)
        val request = createTestRequest(annotations)

        assertTrue("A certificate is requested", request.isDefined())
        assertTrue("Certificate request is renewal", request.get().renew)
        assertEquals("Secret name matches", RENEWAL_CERT, request.get().secretName)
    }

    @Test
    fun testCustomSecretName() {
        val testDomain = "test.k8s.io"
        val annotations = mapOf(
                REQUEST_ANNOTATION to testDomain,
                SECRET_NAME_ANNOTATION to CUSTOM_NAME
        )
        val request = createTestRequest(annotations)

        assertTrue("A certificate is requested", request.isDefined())
        assertFalse("Certificate is not a renewal", request.get().renew)
        assertEquals("Certificate secret name is custom", CUSTOM_NAME, request.get().secretName)
        assertEquals("One domain is included", 1, request.get().domains.size)
        assertEquals("Certificate domain name matches", testDomain, request.get().domains[0])
    }

    @Test
    fun testMultipleDomainRequest() {
        val testDomains = "[\"test1.k8s.io\", \"test2.k8s.io\"]"
        val annotations = mapOf(
                REQUEST_ANNOTATION to testDomains,
                SECRET_NAME_ANNOTATION to "testSecret"
        )
        val request = createTestRequest(annotations)

        assertTrue("A certificate is requested", request.isDefined())
        assertFalse("Certificate is not a renewal", request.get().renew)
        assertEquals("Two domains are included", 2, request.get().domains.size)
        assertThat("Included domains match", request.get().domains,
                hasItems("test1.k8s.io", "test2.k8s.io"))
    }

    @Test
    fun testDomainMismatchRequest() {
        val testDomains = "[\"test1.k8s.io\", \"test2.k8s.io\"]"
        val annotations = mapOf(
                REQUEST_ANNOTATION to testDomains,
                SECRET_NAME_ANNOTATION to MISMATCH_SECRET
        )
        val request = createTestRequest(annotations)

        assertTrue("A certificate is requested", request.isDefined())
        assertTrue("Certificate is a renewal", request.get().renew)
        assertEquals("Two domains are included", 2, request.get().domains.size)
        assertThat("Included domains match", request.get().domains,
                hasItems("test1.k8s.io", "test2.k8s.io"))
    }

    @Test
    fun testDomainRemovedCausesRenewal() {
        val testDomains = "[\"test1.k8s.io\"]"
        val annotations = mapOf (
                REQUEST_ANNOTATION to testDomains,
                SECRET_NAME_ANNOTATION to REMOVED_CERT
        )
        val request = createTestRequest(annotations)

        assertTrue("A certificate is requested", request.isDefined())
        assertTrue("Certificate is a renewal", request.get().renew)
        assertEquals("One domain is included", 1, request.get().domains.size)
        assertThat("Included domain matches", request.get().domains, hasItems("test1.k8s.io"))
    }
}