package in.tazj.k8s.letsencrypt.kubernetes;

import java.security.KeyPair;
import java.util.Map;

import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watcher;
import lombok.extern.slf4j.Slf4j;

/**
 * A Kubernetes API watcher that detects changes to service objects.
 */
@Slf4j
public class ServiceWatcher implements Watcher<Service> {
  final private String ANNOTATION = "acme/certificate";
  final private CertificateManager certificateManager;
  final private KeyPairManager keyPairManager;

  public ServiceWatcher(CertificateManager certificateManager, KeyPairManager keyPairManager) {
    this.certificateManager = certificateManager;
    this.keyPairManager = keyPairManager;
  }

  @Override
  public void eventReceived(Action action, Service service) {
    switch (action) {
      case ADDED:
        if (isCertificateRequest(service)) {
          handleCertificateRequest(service);
        }
        break;
      case MODIFIED:
        if (isCertificateRequest(service)) {
          handleCertificateRequest(service);
        }
        break;
      default:
        log.debug("Unhandled service event of type {}", action.toString());
    }
  }

  private void handleCertificateRequest(Service service) {
    final String certificateName = service.getMetadata().getAnnotations().get(ANNOTATION);
    final String secretName = certificateName.replace('.', '-');

    if (!certificateManager.getCertificate(secretName).isPresent()) {
      log.info("Service {} requesting certificate {}",
          service.getMetadata().getName(), certificateName);

      requestCertificate(certificateName);
    }
  }

  private void requestCertificate(String certificateName) {
    final KeyPair keyPair = keyPairManager.getKeyPair();

  }

  /** Checks if a given service resource is requesting a Letsencrypt certificate. */
  private boolean isCertificateRequest(Service service) {
    final Map<String, String> annotations = service.getMetadata().getAnnotations();

    if (annotations != null && annotations.containsKey(ANNOTATION)) {
      return true;
    }

    return false;
  }

  @Override
  public void onClose(KubernetesClientException e) {

  }
}
