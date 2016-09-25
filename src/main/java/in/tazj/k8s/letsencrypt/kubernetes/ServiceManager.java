package in.tazj.k8s.letsencrypt.kubernetes;

import com.google.common.annotations.VisibleForTesting;

import java.util.Optional;

import in.tazj.k8s.letsencrypt.acme.CertificateRequestHandler;
import in.tazj.k8s.letsencrypt.model.CertificateRequest;
import io.fabric8.kubernetes.api.model.Service;
import io.netty.util.internal.ConcurrentSet;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

import static in.tazj.k8s.letsencrypt.kubernetes.SecretManager.certificateNeedsRenewal;
import static in.tazj.k8s.letsencrypt.model.Constants.ACME_SECRET_NAME_ANNOTATION;
import static in.tazj.k8s.letsencrypt.model.Constants.REQUEST_ANNOTATION;

/**
 * This class deals with reconciling the state for all services in a given namespace.
 */
@Slf4j
public class ServiceManager {
  final private String namespace;
  final private SecretManager secretManager;
  final private CertificateRequestHandler requestHandler;

  /**
   * This set is used to prevent two simultaneous requests for the same certificate.
   */
  final private ConcurrentSet<String> inProgressServices = new ConcurrentSet<>();

  public ServiceManager(String namespace,
                        SecretManager secretManager,
                        CertificateRequestHandler requestHandler) {
    this.namespace = namespace;
    this.secretManager = secretManager;
    this.requestHandler = requestHandler;
  }

  /**
   * Receives a Kubernetes service object, checks for annotations and performs reconciliation.
   */
  public void reconcileService(Service service) {
    val serviceName = service.getMetadata().getName();
    log.debug("Reconciliation request for {}", serviceName);
    if (isCertificateRequest(service) && !inProgressServices.contains(serviceName)) {
      new Thread(() -> {
        try {
          inProgressServices.add(serviceName);
          final Optional<CertificateRequest> request = prepareCertificateRequest(service);
          request.ifPresent(this::handleCertificateRequest);
        } finally {
          inProgressServices.remove(serviceName);
        }
      }).start();
    }
  }

  /**
   * This function examines the annotations of a service requesting a certificate, the current state
   * of matching secrets in Kubernetes and potentially certificate expiry time.
   *
   * It then creates a CertificateRequest object that describes the steps that need to be performed
   * for reconciliation.
   */
  @VisibleForTesting
  public Optional<CertificateRequest> prepareCertificateRequest(Service service) {
    val certificateName = service.getMetadata().getAnnotations().get(REQUEST_ANNOTATION);
    val serviceName = service.getMetadata().getName();
    val secretName = getSecretName(service);
    val secretOptional = secretManager.getSecret(namespace, secretName);
    val requestBuilder = CertificateRequest.builder()
        .secretName(secretName)
        .certificateName(certificateName);

    if (!secretOptional.isPresent()) {
      log.info("Service {} requesting certificate {}", serviceName, certificateName);
      requestBuilder.renew(false);
    } else if (certificateNeedsRenewal(namespace, secretOptional.get())) {
      log.info("Renewing certificate {} requested by {}", certificateName, serviceName);
      requestBuilder.renew(true);
    } else {
      log.debug("Certificate {} for service {} already exists", certificateName, serviceName);
      return Optional.empty();
    }

    return Optional.of(requestBuilder.build());
  }

  /**
   * This function performs a certificate request as determined by prepareCertificateRequest().
   *
   * The resulting certificate will either be added to the Kubernetes cluster as a new secret, or
   * the existing secret will be updated with the new certificate.
   */
  private void handleCertificateRequest(CertificateRequest request) {
    if (request.getRenew()) {
      val certificateResponse = requestHandler.requestCertificate(request.getCertificateName());
      secretManager.updateCertificate(namespace, request.getSecretName(), certificateResponse);
    } else {
      val certificateResponse = requestHandler.requestCertificate(request.getCertificateName());
      secretManager.insertCertificate(namespace, request.getSecretName(), certificateResponse);
    }
  }

  /**
   * Checks if a given service resource is requesting a Letsencrypt certificate.
   */
  private static boolean isCertificateRequest(Service service) {
    val annotations = service.getMetadata().getAnnotations();
    return (annotations != null && annotations.containsKey(REQUEST_ANNOTATION));
  }

  /**
   * Determines the matching Kubernetes secret name for this certificate.
   *
   * Users may override the default secret name by specifying an acme/secretName annotation.
   */
  private static String getSecretName(Service service) {
    val annotations = service.getMetadata().getAnnotations();
    val secretAnnotation = Optional.ofNullable(annotations.get(ACME_SECRET_NAME_ANNOTATION));

    if (secretAnnotation.isPresent()) {
      return secretAnnotation.get();
    } else {
      // This annotation is guaranteed to be set after this point as this function is called after
      // isCertificateRequest().
      val certificateName = annotations.get(REQUEST_ANNOTATION);
      val secretName = certificateName.replace('.', '-') + "-tls";
      return secretName;
    }
  }
}
