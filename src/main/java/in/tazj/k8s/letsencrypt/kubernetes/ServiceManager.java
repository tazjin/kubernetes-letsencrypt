package in.tazj.k8s.letsencrypt.kubernetes;

import in.tazj.k8s.letsencrypt.acme.CertificateRequestHandler;
import io.fabric8.kubernetes.api.model.Service;
import io.netty.util.internal.ConcurrentSet;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

/**
 * This class deals with reconciling the state for all services in a given namespace.
 */
@Slf4j
public class ServiceManager {
  final private String ANNOTATION = "acme/certificate";
  final private String namespace;
  final private CertificateManager certificateManager;
  final private CertificateRequestHandler requestHandler;

  /** This set is used to prevent two simultaneous requests for the same certificate. */
  final private ConcurrentSet<String> inProgressServices = new ConcurrentSet<>();

  public ServiceManager(String namespace,
                        CertificateManager certificateManager,
                        CertificateRequestHandler requestHandler) {
    this.namespace = namespace;
    this.certificateManager = certificateManager;
    this.requestHandler = requestHandler;
  }

  /** Receives a Kubernetes service object, checks for annotations and performs reconciliation. */
  public void reconcileService(Service service) {
    val serviceName = service.getMetadata().getName();
    log.debug("Reconciliation request for {}", serviceName);
    if (isCertificateRequest(service) && !inProgressServices.contains(serviceName)) {
      new Thread(() -> {
        try {
          inProgressServices.add(serviceName);
          handleCertificateRequest(service);
        } finally {
          inProgressServices.remove(serviceName);
        }
      }).start();
    }
  }

  private void handleCertificateRequest(Service service) {
    val certificateName = service.getMetadata().getAnnotations().get(ANNOTATION);
    val secretName = certificateName.replace('.', '-') + "-tls";
    val serviceName = service.getMetadata().getName();

    if (!certificateManager.getCertificate(namespace, secretName).isPresent()) {
      log.info("Service {} requesting certificate {}", serviceName, certificateName);

      val certificateFiles = requestHandler.requestCertificate(certificateName);
      certificateManager.insertCertificate(namespace, secretName, certificateFiles);
    } else {
      log.debug("Certificate {} for service {} already exists", certificateName, serviceName);
    }
  }

  /** Checks if a given service resource is requesting a Letsencrypt certificate. */
  private boolean isCertificateRequest(Service service) {
    val annotations = service.getMetadata().getAnnotations();
    return (annotations != null && annotations.containsKey(ANNOTATION));
  }
}
