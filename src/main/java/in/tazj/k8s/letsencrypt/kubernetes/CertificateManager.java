package in.tazj.k8s.letsencrypt.kubernetes;

import java.util.Map;
import java.util.Optional;

import in.tazj.k8s.letsencrypt.util.LetsencryptException;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.extern.slf4j.Slf4j;

/**
 * Manages certificates in the form of secrets in the Kubernetes API.
 */
@Slf4j
public class CertificateManager {
  final private KubernetesClient client;

  public CertificateManager(KubernetesClient client) {
    this.client = client;
  }

  public Optional<Secret> getCertificate(String certificateName) {
    // TODO: .get() returns null but .list() works, not sure why yet.
    final Optional<Secret> secret = client.secrets().list().getItems().stream()
        .filter(s -> s.getMetadata().getName().equals(certificateName))
        .findFirst();
    return secret;
  }

  /** Insert a specified certificate in the Kubernetes cluster. */
  public Secret insertCertificate(String certificateName, Map<String, String> files) {
    /*final Optional<Secret> currentSecret = getCertificate(certificateName);
    if (currentSecret.isPresent()) {
      log.info("Updating of certificates not yet implemented");
      throw new LetsencryptException("NYI");
    }*/

    final Secret secret = client.secrets().inNamespace("default")
        .createNew()
        .withNewMetadata()
          .withName(certificateName)
        .endMetadata()
        .withData(files)
        .done();

    return secret;
  }
}
