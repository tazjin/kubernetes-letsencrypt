package in.tazj.k8s.letsencrypt.kubernetes;

import com.google.common.collect.ImmutableMap;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import in.tazj.k8s.letsencrypt.acme.CertificateRequestHandler;
import in.tazj.k8s.letsencrypt.model.CertificateRequest;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.Service;
import lombok.val;

import static in.tazj.k8s.letsencrypt.model.Constants.SECRET_NAME_ANNOTATION;
import static in.tazj.k8s.letsencrypt.model.Constants.EXPIRY_ANNOTATION;
import static in.tazj.k8s.letsencrypt.model.Constants.REQUEST_ANNOTATION;
import static org.hamcrest.CoreMatchers.hasItems;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.when;

public class ServiceManagerTest {
  private ServiceManager serviceManager = null;
  final private String EXISTING_CERT = "existing-k8s-io-tls";
  final private String RENEWAL_CERT = "renewal-k8s-io-tls";
  final private String CUSTOM_NAME = "customSecretName";

  @Before
  public void setup() {
    val secretManager = getMockedSecretManager();
    val requestHandler = Mockito.mock(CertificateRequestHandler.class);

    serviceManager = new ServiceManager("default", secretManager, requestHandler);
  }

  private SecretManager getMockedSecretManager() {
    val existingSecret = prepareExistingSecret();
    val renewalSecret = prepareRenewalSecret();

    val secretManager = Mockito.mock(SecretManager.class);
    when(secretManager.getSecret(anyString(), anyString())).thenReturn(Optional.empty());
    when(secretManager.getSecret(eq("default"), eq(EXISTING_CERT))).thenReturn(existingSecret);
    when(secretManager.getSecret(eq("default"), eq(RENEWAL_CERT))).thenReturn(renewalSecret);
    return secretManager;
  }

  /** Prepare a plain, mocked secret for an existing certificate. */
  private Optional<Secret> prepareExistingSecret() {
    val secretMeta = Mockito.mock(ObjectMeta.class);
    when(secretMeta.getName()).thenReturn(EXISTING_CERT);

    val secret = Mockito.mock(Secret.class);
    when(secret.getMetadata()).thenReturn(secretMeta);

    return Optional.of(secret);
  }

  /** Prepare a mocked secret for an expired certificate. */
  private Optional<Secret> prepareRenewalSecret() {
    val expiryDate = LocalDate.now().minusDays(1);
    val annotations = ImmutableMap.of(EXPIRY_ANNOTATION, expiryDate.toString());
    val secretMeta = Mockito.mock(ObjectMeta.class);
    when(secretMeta.getName()).thenReturn(RENEWAL_CERT);
    when(secretMeta.getAnnotations()).thenReturn(annotations);

    val secret = Mockito.mock(Secret.class);
    when(secret.getMetadata()).thenReturn(secretMeta);

    return Optional.of(secret);
  }

  @Test
  public void testNormalCertificateRequest() {
    val testDomain = "test.k8s.io";
    val annotations = ImmutableMap.of(REQUEST_ANNOTATION, testDomain);
    val request = createTestRequest(annotations);
    val expectedSecret = "test-k8s-io-tls";

    assertTrue("Service manager builds a request", request.isPresent());
    assertFalse("Request is not a renewal", request.get().getRenew());
    assertEquals("Request secret name matches", expectedSecret, request.get().getSecretName());
    assertEquals("One domain is included", 1, request.get().getDomains().size());
    assertEquals("Request domain name matches", testDomain, request.get().getDomains().get(0));
  }

  @Test
  public void testExistingCertificateRequest() {
    val testDomain = "existing.k8s.io";
    val annotations = ImmutableMap.of(REQUEST_ANNOTATION, testDomain);
    val request = createTestRequest(annotations);

    assertFalse("No certificate is requested", request.isPresent());
  }

  @Test
  public void testCertificateRenewalRequest() {
    val testDomain = "renewal.k8s.io";
    val annotations = ImmutableMap.of(REQUEST_ANNOTATION, testDomain);
    val request = createTestRequest(annotations);

    assertTrue("A certificate is requested", request.isPresent());
    assertTrue("Certificate request is renewal", request.get().getRenew());
    assertEquals("Secret name matches", RENEWAL_CERT, request.get().getSecretName());
  }

  @Test
  public void testCustomSecretName() {
    val testDomain = "test.k8s.io";
    val annotations = ImmutableMap.of(
        REQUEST_ANNOTATION, testDomain,
        SECRET_NAME_ANNOTATION, CUSTOM_NAME);
    val request = createTestRequest(annotations);

    assertTrue("A certificate is requested", request.isPresent());
    assertFalse("Certificate is not a renewal", request.get().getRenew());
    assertEquals("Certificate secret name is custom", CUSTOM_NAME, request.get().getSecretName());
    assertEquals("One domain is included", 1, request.get().getDomains().size());
    assertEquals("Certificate domain name matches", testDomain, request.get().getDomains().get(0));
  }

  @Test
  public void testMultipleDomainRequest() {
    val testDomains = "[\"test1.k8s.io\", \"test2.k8s.io\"]";
    val annotations = ImmutableMap.of(
        REQUEST_ANNOTATION, testDomains,
        SECRET_NAME_ANNOTATION, "testSecret");
    val request = createTestRequest(annotations);

    assertTrue("A certificate is requested", request.isPresent());
    assertFalse("Certificate is not a renewal", request.get().getRenew());
    assertEquals("Two domains are included", 2, request.get().getDomains().size());
    assertThat("Included domains match", request.get().getDomains(),
        hasItems("test1.k8s.io", "test2.k8s.io"));
  }

  private Optional<CertificateRequest> createTestRequest (Map<String, String> annotations) {
    val testMetadata = Mockito.mock(ObjectMeta.class);
    when(testMetadata.getAnnotations()).thenReturn(annotations);
    when(testMetadata.getName()).thenReturn("testService");
    val testService = Mockito.mock(Service.class);
    when(testService.getMetadata()).thenReturn(testMetadata);
    return serviceManager.prepareCertificateRequest(testService);
  }
}