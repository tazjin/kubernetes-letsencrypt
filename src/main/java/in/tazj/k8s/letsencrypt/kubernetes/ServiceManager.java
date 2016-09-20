package in.tazj.k8s.letsencrypt.kubernetes;

import org.joda.time.LocalDate;

import java.util.Optional;

import in.tazj.k8s.letsencrypt.acme.CertificateRequestHandler;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.Service;
import io.netty.util.internal.ConcurrentSet;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

import static in.tazj.k8s.letsencrypt.model.Constants.REQUEST_ANNOTATION;
import static in.tazj.k8s.letsencrypt.model.Constants.EXPIRY_ANNOTATION;

/**
 * This class deals with reconciling the state for all services in a given namespace.
 */
@Slf4j
public class ServiceManager {
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
    val certificateName = service.getMetadata().getAnnotations().get(REQUEST_ANNOTATION);
    val secretName = certificateName.replace('.', '-') + "-tls";
    val serviceName = service.getMetadata().getName();
    val secretOptional = certificateManager.getCertificate(namespace, secretName);

    if (!secretOptional.isPresent()) {
      log.info("Service {} requesting certificate {}", serviceName, certificateName);

      val certificateResponse = requestHandler.requestCertificate(certificateName);
      certificateManager.insertCertificate(namespace, secretName, certificateResponse);
    } else if (checkCertificateNeedsRenewal(secretOptional.get())) {
      log.info("Renewing certificate {} requested by {}", certificateName, serviceName);

      val certificateResponse = requestHandler.requestCertificate(certificateName);
      certificateManager.updateCertificate(namespace, secretName, certificateResponse);
    } else {
      log.debug("Certificate {} for service {} already exists", certificateName, serviceName);
    }
  }

  /** Checks if a given service resource is requesting a Letsencrypt certificate. */
  private boolean isCertificateRequest(Service service) {
    val annotations = service.getMetadata().getAnnotations();
    return (annotations != null && annotations.containsKey(REQUEST_ANNOTATION));
  }

  /** Checks whether a certificate needs renewal (expires within some days from now). */
  private boolean checkCertificateNeedsRenewal(Secret secret) {
    val expiryDate = getExpiryDate(secret);

    if (expiryDate.isPresent()) {
      return LocalDate.now().isAfter(expiryDate.get().minusDays(2));
    } else {
      log.warn("No expiry date set on secret {} in namespace {}!",
          secret.getMetadata().getName(), namespace);
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
