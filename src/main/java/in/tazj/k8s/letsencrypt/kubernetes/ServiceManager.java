package in.tazj.k8s.letsencrypt.kubernetes;

import com.google.common.annotations.VisibleForTesting;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.util.List;
import java.util.Optional;

import autovalue.shaded.com.google.common.common.collect.ImmutableList;
import in.tazj.k8s.letsencrypt.acme.CertificateRequestHandler;
import in.tazj.k8s.letsencrypt.model.CertificateRequest;
import in.tazj.k8s.letsencrypt.util.LetsencryptException;
import io.fabric8.kubernetes.api.model.Service;
import io.netty.util.internal.ConcurrentSet;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

import static in.tazj.k8s.letsencrypt.kubernetes.SecretManager.certificateNeedsRenewal;
import static in.tazj.k8s.letsencrypt.model.Constants.SECRET_NAME_ANNOTATION;
import static in.tazj.k8s.letsencrypt.model.Constants.REQUEST_ANNOTATION;

/**
 * This class deals with reconciling the state for all services in a given namespace.
 */
@Slf4j
public class ServiceManager {
  final static private Gson gson = new Gson();
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
    val domains = getCertificateDomains(service);
    val serviceName = service.getMetadata().getName();
    val secretAnnotation = service.getMetadata().getAnnotations().get(SECRET_NAME_ANNOTATION);
    val secretName = getSecretName(domains, Optional.ofNullable(secretAnnotation));
    val secretOptional = secretManager.getSecret(namespace, secretName);
    val requestBuilder = CertificateRequest.builder()
        .secretName(secretName)
        .domains(domains);

    if (!secretOptional.isPresent()) {
      log.info("Service {} requesting certificates: {}", serviceName, domains.toString());
      requestBuilder.renew(false);
    } else if (certificateNeedsRenewal(namespace, secretOptional.get())) {
      log.info("Renewing certificates {} requested by {}", domains.toString(), serviceName);
      requestBuilder.renew(true);
    } else {
      log.debug("Certificate for {} requested by {} already exists",
          domains.toString(), serviceName);
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
      val certificateResponse = requestHandler.requestCertificate(request.getDomains());
      secretManager.updateCertificate(namespace, request.getSecretName(), certificateResponse);
    } else {
      val certificateResponse = requestHandler.requestCertificate(request.getDomains());
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
  private static String getSecretName(List<String> domains, Optional<String> secretName) {
    if (secretName.isPresent()) {
      return secretName.get();
    }

    if (domains.size() >= 2) {
      val error = "acme/secretName must be specified if multiple domains are requested!";
      log.error(error);
      throw new LetsencryptException(error);
    }

    val domain = domains.get(0);
    val domainSecretName = domain.replace('.', '-') + "-tls";
    return domainSecretName;
  }

  /**
   * Determines the domain names for which a certificate has been requested via service annotations.
   *
   * If the annotation contains a JSON array of strings, each string will be considered a separate
   * domain name and they will be added into a SAN certificate.
   */
  private static List<String> getCertificateDomains(Service service) {
    val requestAnnotation = service.getMetadata().getAnnotations().get(REQUEST_ANNOTATION);

    if (requestAnnotation.startsWith("[")) {
      val type = new TypeToken<List<String>>() {}.getType();
      final List<String> domains = gson.fromJson(requestAnnotation, type);

      if (domains.size() == 0) {
        val error = "No domains have been specified!";
        log.error(error);
        throw new LetsencryptException(error);
      }

      return domains;
    } else {
      return ImmutableList.of(requestAnnotation);
    }
  }
}
