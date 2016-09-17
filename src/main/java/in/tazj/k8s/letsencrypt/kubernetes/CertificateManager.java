package in.tazj.k8s.letsencrypt.kubernetes;

import java.util.Optional;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;

/**
 * Manages certificates in the form of secrets in the Kubernetes API.
 */
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

  // TODO: Implement this with annotations on the secret
  public boolean shouldCertificateBeRefreshed(Secret secret) {
    return true;
  }
}
