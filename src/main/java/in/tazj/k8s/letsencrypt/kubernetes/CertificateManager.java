package in.tazj.k8s.letsencrypt.kubernetes;

import com.google.common.collect.ImmutableMap;

import org.joda.time.LocalDate;

import java.util.Optional;

import in.tazj.k8s.letsencrypt.model.CertificateResponse;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

import static in.tazj.k8s.letsencrypt.model.Constants.EXPIRY_ANNOTATION;

/**
 * Manages certificates in the form of secrets in the Kubernetes API.
 */
@Slf4j
public class CertificateManager {
  final private KubernetesClient client;

  public CertificateManager(KubernetesClient client) {
    this.client = client;
  }

  public Optional<Secret> getCertificate(String namespace, String certificateName) {
    // TODO: .get() returns null but .list() works, not sure why yet.
    final Optional<Secret> secret = client.secrets().inNamespace(namespace)
        .list().getItems().stream()
        .filter(s -> s.getMetadata().getName().equals(certificateName))
        .findFirst();
    return secret;
  }

  /** Insert a specified certificate in the Kubernetes cluster. */
  public Secret insertCertificate(String namespace, String secretName,
                                  CertificateResponse certificate) {
    val expiryDate = LocalDate.fromDateFields(certificate.getExpiryDate());
    val annotations = ImmutableMap.of(EXPIRY_ANNOTATION, expiryDate.toString());
    val secret = client.secrets().inNamespace(namespace)
        .createNew()
        .withNewMetadata()
          .withName(secretName)
          .withAnnotations(annotations)
        .endMetadata()
        .withData(certificate.getCertificateFiles())
        .done();

    log.info("Inserted secret {} into namespace {}", secretName, namespace);
    return secret;
  }

  public Secret updateCertificate(String namespace,
                                  String secretName,
                                  CertificateResponse certificate) {
    val expiryDate = LocalDate.fromDateFields(certificate.getExpiryDate());
    val secret = client.secrets().inNamespace(namespace).withName(secretName).edit()
        .editMetadata()
          .removeFromAnnotations(EXPIRY_ANNOTATION)
          .addToAnnotations(EXPIRY_ANNOTATION, expiryDate.toString())
        .endMetadata()
        .withData(certificate.getCertificateFiles())
        .done();

    log.info("Updated secret {} in namespace {}", secretName, namespace);
    return secret;
  }
}
