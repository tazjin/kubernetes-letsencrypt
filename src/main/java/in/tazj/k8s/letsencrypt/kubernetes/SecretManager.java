package in.tazj.k8s.letsencrypt.kubernetes;

import com.google.common.collect.ImmutableMap;

import org.joda.time.LocalDate;

import java.util.Optional;

import in.tazj.k8s.letsencrypt.model.CertificateRequest;
import in.tazj.k8s.letsencrypt.model.CertificateResponse;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

import static in.tazj.k8s.letsencrypt.model.Constants.ACME_CA_ANNOTATION;
import static in.tazj.k8s.letsencrypt.model.Constants.EXPIRY_ANNOTATION;
import static in.tazj.k8s.letsencrypt.model.Constants.REQUEST_ANNOTATION;

/**
 * Manages certificates in the form of secrets in the Kubernetes API.
 */
@Slf4j
public class SecretManager {
  final private KubernetesClient client;

  public SecretManager(KubernetesClient client) {
    this.client = client;
  }

  public Optional<Secret> getSecret(String namespace, String secretName) {
    // TODO: .get() returns null but .list() works, not sure why yet.
    final Optional<Secret> secret = client.secrets().inNamespace(namespace)
        .list().getItems().stream()
        .filter(s -> s.getMetadata().getName().equals(secretName))
        .findFirst();
    return secret;
  }

  /**
   * Insert a specified certificate in the Kubernetes cluster.
   */
  public void insertCertificate(String namespace, String secretName,
                                CertificateResponse certificate) {
    val expiryDate = LocalDate.fromDateFields(certificate.getExpiryDate());
    val annotations = ImmutableMap.of(
        EXPIRY_ANNOTATION, expiryDate.toString(),
        ACME_CA_ANNOTATION, certificate.getCa());

    client.secrets().inNamespace(namespace)
        .createNew()
        .withNewMetadata()
          .withName(secretName)
          .withAnnotations(annotations)
        .endMetadata()
        .withData(certificate.getCertificateFiles())
        .done();

    log.info("Inserted secret {} into namespace {}", secretName, namespace);
  }

  public void updateCertificate(String namespace,
                                String secretName,
                                CertificateResponse certificate) {
    val expiryDate = LocalDate.fromDateFields(certificate.getExpiryDate());

    client.secrets().inNamespace(namespace).withName(secretName).edit()
        .editMetadata()
          .removeFromAnnotations(EXPIRY_ANNOTATION)
          .addToAnnotations(EXPIRY_ANNOTATION, expiryDate.toString())
          .addToAnnotations(ACME_CA_ANNOTATION, certificate.getCa())
        .endMetadata()
        .withData(certificate.getCertificateFiles())
        .done();

    log.info("Updated secret {} in namespace {}", secretName, namespace);
  }

  /**
   * Checks whether a certificate needs renewal. Renewal can be caused either by certificate expiry
   * or if the list of domains requested for a SAN certificate changes.
   */
  public static boolean certificateNeedsRenewal(String requestAnnotation,
                                                Secret secret) {
    val isExpiring = certificateIsExpiring(secret);
    val domainsChanged = haveDomainsChanged(requestAnnotation, secret);

    return (isExpiring || domainsChanged);
  }

  private static boolean haveDomainsChanged(String requestAnnotation, Secret secret) {
    val annotations = secret.getMetadata().getAnnotations();

    if (annotations != null ) {
      val secretDomains = annotations.get(REQUEST_ANNOTATION);

      if (secretDomains != null) {
        val domainsChanged = !(requestAnnotation.equals(secretDomains));
        return domainsChanged;
      }
    }

    log.warn("acme/certificate annotation missing on secret {}!", secret.getMetadata().getName());
    return false;
  }

  public static boolean certificateIsExpiring(Secret secret) {
    val expiryDate = getExpiryDate(secret);

    if (expiryDate.isPresent()) {
      return LocalDate.now().isAfter(expiryDate.get().minusDays(2));
    } else {
      log.warn("No expiry date set on secret {} in namespace {}!",
          secret.getMetadata().getName(), secret.getMetadata().getNamespace());
      return false;
    }
  }

  private static Optional<LocalDate> getExpiryDate(Secret secret) {
    val annotations = secret.getMetadata().getAnnotations();

    if (annotations != null && annotations.get(EXPIRY_ANNOTATION) != null) {
      val annotation = annotations.get(EXPIRY_ANNOTATION);
      return Optional.of(new LocalDate(annotation));
    } else {
      return Optional.empty();
    }
  }
}
