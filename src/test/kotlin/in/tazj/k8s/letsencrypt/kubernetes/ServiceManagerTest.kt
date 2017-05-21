package `in`.tazj.k8s.letsencrypt.kubernetes

import `in`.tazj.k8s.letsencrypt.acme.CertificateRequestHandler
import `in`.tazj.k8s.letsencrypt.kubernetes.SecretManager
import `in`.tazj.k8s.letsencrypt.model.CertificateRequest
import `in`.tazj.k8s.letsencrypt.model.EXPIRY_ANNOTATION
import `in`.tazj.k8s.letsencrypt.model.REQUEST_ANNOTATION
import `in`.tazj.k8s.letsencrypt.model.SECRET_NAME_ANNOTATION
import io.fabric8.kubernetes.api.model.ObjectMeta
import io.fabric8.kubernetes.api.model.Secret
import io.fabric8.kubernetes.api.model.Service
import org.funktionale.option.Option
import org.funktionale.option.toOption
import org.hamcrest.CoreMatchers.hasItems
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Matchers.anyString
import org.mockito.Matchers.eq
import org.mockito.Mockito
import org.mockito.Mockito.`when`
import java.time.LocalDate


class ServiceManagerTest {
    private lateinit var serviceManager: ServiceManager
    private val EXISTING_CERT = "existing-k8s-io-tls"
    private val RENEWAL_CERT = "renewal-k8s-io-tls"
    private val CUSTOM_NAME = "customSecretName"
    private val MISMATCH_SECRET = "mismatchSecret"

    @Before
    fun setup() {
        val secretManager = getMockedSecretManager()
        val requestHandler = Mockito.mock(CertificateRequestHandler::class.java)

        serviceManager = ServiceManager("default", secretManager, requestHandler)
    }

    private fun getMockedSecretManager(): SecretManager {
        val existingSecret = prepareExistingSecret()
        val renewalSecret = prepareRenewalSecret()
        val mismatchSecret = prepareDomainMismatchSecret()

        val secretManager = Mockito.mock(SecretManager::class.java)
        `when`(secretManager.getSecret(anyString(), anyString())).thenReturn(Option.None)
        `when`(secretManager.getSecret(eq("default"), eq(EXISTING_CERT))).thenReturn(existingSecret)
        `when`(secretManager.getSecret(eq("default"), eq(RENEWAL_CERT))).thenReturn(renewalSecret)
        `when`(secretManager.getSecret(eq("default"), eq(MISMATCH_SECRET))).thenReturn(mismatchSecret)
        return secretManager
    }

    /** Prepare a plain, mocked secret for an existing certificate.  */
    private fun prepareExistingSecret(): Option<Secret> {
        val annotations = mapOf(REQUEST_ANNOTATION to "[\"existing.k8s.io\"]")
        val secretMeta = Mockito.mock(ObjectMeta::class.java)
        `when`(secretMeta.name).thenReturn(EXISTING_CERT)
        `when`(secretMeta.annotations).thenReturn(annotations)

        val secret = Mockito.mock(Secret::class.java)
        `when`(secret.metadata).thenReturn(secretMeta)

        return secret.toOption()
    }

    /** Prepare a mocked secret for an expired certificate.  */
    private fun prepareRenewalSecret(): Option<Secret> {
        val expiryDate = LocalDate.now().minusDays(1)
        val annotations = mapOf(EXPIRY_ANNOTATION to expiryDate.toString())
        val secretMeta = Mockito.mock(ObjectMeta::class.java)
        `when`(secretMeta.name).thenReturn(RENEWAL_CERT)
        `when`(secretMeta.annotations).thenReturn(annotations)

        val secret = Mockito.mock(Secret::class.java)
        `when`(secret.metadata).thenReturn(secretMeta)

        return secret.toOption()
    }

    /** Prepare a mocked secret with domain mismatch.  */
    private fun prepareDomainMismatchSecret(): Option<Secret> {
        val annotations = mapOf(
                REQUEST_ANNOTATION to "[\"test1.k8s.io\", \"test3.k8s.io\"]"
        )

        val secretMeta = Mockito.mock(ObjectMeta::class.java)
        `when`(secretMeta.name).thenReturn(MISMATCH_SECRET)
        `when`(secretMeta.annotations).thenReturn(annotations)

        val secret = Mockito.mock(Secret::class.java)
        `when`(secret.metadata).thenReturn(secretMeta)

        return secret.toOption()
    }

    private fun createTestRequest(annotations: Map<String, String>): Option<CertificateRequest> {
        val testMetadata = Mockito.mock(ObjectMeta::class.java)
        `when`(testMetadata.annotations).thenReturn(annotations)
        `when`(testMetadata.name).thenReturn("testService")
        val testService = Mockito.mock(Service::class.java)
        `when`(testService.getMetadata()).thenReturn(testMetadata)
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
        assertEquals("Request domain name matches", testDomain, request.get().domains.get(0))
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

}