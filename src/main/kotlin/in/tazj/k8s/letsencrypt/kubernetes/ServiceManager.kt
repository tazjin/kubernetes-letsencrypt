package `in`.tazj.k8s.letsencrypt.kubernetes

import `in`.tazj.k8s.letsencrypt.acme.CertificateRequestHandler
import `in`.tazj.k8s.letsencrypt.kubernetes.SecretManager.Companion.certificateNeedsRenewal
import `in`.tazj.k8s.letsencrypt.model.CertificateRequest
import `in`.tazj.k8s.letsencrypt.model.REQUEST_ANNOTATION
import `in`.tazj.k8s.letsencrypt.model.SECRET_NAME_ANNOTATION
import `in`.tazj.k8s.letsencrypt.util.LetsencryptException
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.fabric8.kubernetes.api.model.Service
import org.funktionale.option.Option
import org.funktionale.option.toOption
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicReference

/**
 * This class deals with reconciling the state for all services in a given namespace.
 */
class ServiceManager(
        val namespace: String,
        val secretManager: SecretManager,
        val requestHandler: CertificateRequestHandler
) {
    private val log = LoggerFactory.getLogger(this::class.java)
    private val gson = Gson()

    /**
     * This set is used to prevent two simultaneous requests for the same certificate.
     */
    private val inProgressServices: AtomicReference<Set<String>> = AtomicReference(setOf())

    /**
     * Receives a Kubernetes service object, checks for annotations and performs reconciliation.
     */
    fun reconcileService(service: Service) {
        val serviceName = service.metadata.name
        log.debug("Reconciliation request for {}", serviceName)

        if (isCertificateRequest(service) && !inProgressServices.get().contains(serviceName)) {
            Thread {
                try {
                    inProgressServices.updateAndGet { it.plus(serviceName) }
                    val request = prepareCertificateRequest(service)
                    request.forEach { handleCertificateRequest(it) }
                } finally {
                    inProgressServices.updateAndGet { it.minus(serviceName) }
                }
            }.start()
        }
    }

    /**
     * This function performs a certificate request as determined by prepareCertificateRequest().

     * The resulting certificate will either be added to the Kubernetes cluster as a new secret, or
     * the existing secret will be updated with the new certificate.
     */
    private fun handleCertificateRequest(request: CertificateRequest) {
        if (request.renew) {
            val certificateResponse = requestHandler.requestCertificate(request.domains)
            secretManager.updateCertificate(namespace, request.secretName, certificateResponse)
        } else {
            val certificateResponse = requestHandler.requestCertificate(request.domains)
            secretManager.insertCertificate(namespace, request.secretName, certificateResponse)
        }
    }

    /**
     * This function examines the annotations of a service requesting a certificate, the current state
     * of matching secrets in Kubernetes and potentially certificate expiry time.

     * It then creates a CertificateRequest object that describes the steps that need to be performed
     * for reconciliation.
     */
    fun prepareCertificateRequest(service: Service): Option<CertificateRequest> {
        val requestAnnotation = service.metadata.annotations[REQUEST_ANNOTATION]!!
        val domains = getCertificateDomains(requestAnnotation)
        val serviceName = service.metadata.name
        val secretAnnotation = service.metadata.annotations[SECRET_NAME_ANNOTATION].toOption()
        val secretName = getSecretName(domains, secretAnnotation)
        val secretOption = secretManager.getSecret(namespace, secretName)
        val renew: Boolean = when {
            !secretOption.isDefined() -> {
                log.info("Service {} requesting certificates: {}", serviceName, domains.toString())
                false
            }
            certificateNeedsRenewal(domains, secretOption.get()) -> {
                log.info("Renewal of certificates {} requested by {}", domains.toString(), serviceName)
                true
            }
            else -> {
                log.debug("Certificate for {} requested by {} already exists",
                        domains.toString(), serviceName)
                return Option.None
            }
        }

        return CertificateRequest(secretName, domains, renew).toOption()
    }

    /**
     * Determines the matching Kubernetes secret name for this certificate.

     * Users may override the default secret name by specifying an acme/secretName annotation.
     */
    private fun getSecretName(domains: List<String>, secretName: Option<String>): String {
        if (secretName.isDefined()) {
            return secretName.get()
        }

        if (domains.size >= 2) {
            val error = "acme/secretName must be specified if multiple domains are requested!"
            log.error(error)
            throw LetsencryptException(error)
        }

        val domain = domains[0]
        val domainSecretName = domain.replace('.', '-') + "-tls"
        return domainSecretName
    }

    /**
     * Checks if a given service resource is requesting a Let's Encrypt certificate.
     */
    private fun isCertificateRequest(service: Service): Boolean {
        val annotations = service.metadata.annotations
        return annotations != null && annotations.containsKey(REQUEST_ANNOTATION)
    }

    /**
     * Determines the domain names for which a certificate has been requested via service annotations.

     * If the annotation contains a JSON array of strings, each string will be considered a separate
     * domain name and they will be added into a SAN certificate.
     */
    private fun getCertificateDomains(requestAnnotation: String): List<String> {
        if (requestAnnotation.startsWith("[")) {
            val type = object : TypeToken<List<String>>() {}.getType()
            val domains: List<String> = gson.fromJson(requestAnnotation, type)

            if (domains.isEmpty()) {
                val error = "No domains have been specified!"
                log.error(error)
                throw LetsencryptException(error)
            }

            return domains
        } else {
            return listOf(requestAnnotation)
        }
    }
}