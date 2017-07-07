package `in`.tazj.k8s.letsencrypt.kubernetes

import `in`.tazj.k8s.letsencrypt.model.ACME_CA_ANNOTATION
import `in`.tazj.k8s.letsencrypt.model.CertificateResponse
import `in`.tazj.k8s.letsencrypt.model.EXPIRY_ANNOTATION
import `in`.tazj.k8s.letsencrypt.model.REQUEST_ANNOTATION
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.fabric8.kubernetes.api.model.Secret
import io.fabric8.kubernetes.client.KubernetesClient
import org.funktionale.option.Option
import org.funktionale.option.getOrElse
import org.funktionale.option.toOption
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.ZoneId

/**
 * Manages certificates in the form of secrets in the Kubernetes API.
 */
class SecretManager(val client: KubernetesClient) {
    fun getSecret(namespace: String, secretName: String): Option<Secret> {
        // TODO: .get() returns null but .list() works, not sure why yet.
        return client.secrets().inNamespace(namespace)
                .list().items
                .filter { it.metadata.name == secretName }
                .lastOrNull().toOption()
    }

    /**
     * Insert a specified certificate in the Kubernetes cluster.
     */
    fun insertCertificate(namespace: String, secretName: String, certificate: CertificateResponse) {
        val expiryDate = certificate.expiryDate
                .toInstant()
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
        val domains = gson.toJson(certificate.domains)
        val annotations = mapOf(
                EXPIRY_ANNOTATION to expiryDate.toString(),
                ACME_CA_ANNOTATION to certificate.ca,
                REQUEST_ANNOTATION to domains
        )

        client.secrets().inNamespace(namespace)
                .createNew()
                .withNewMetadata()
                .withName(secretName)
                .withAnnotations(annotations)
                .endMetadata()
                .withData(certificate.certificateFiles)
                .done()

        log.info("Inserted secret {} into namespace {}", secretName, namespace)
    }

    fun updateCertificate(namespace: String,
                          secretName: String,
                          certificate: CertificateResponse) {
        val expiryDate = certificate.expiryDate
                .toInstant()
                .atZone(ZoneId.systemDefault())
                .toLocalDate()

        val domains = gson.toJson(certificate.domains)

        client.secrets().inNamespace(namespace).withName(secretName).edit()
                .editMetadata()
                .removeFromAnnotations(EXPIRY_ANNOTATION)
                .addToAnnotations(EXPIRY_ANNOTATION, expiryDate.toString())
                .addToAnnotations(ACME_CA_ANNOTATION, certificate.ca)
                .addToAnnotations(REQUEST_ANNOTATION, domains)
                .endMetadata()
                .withData(certificate.certificateFiles)
                .done()

        log.info("Updated secret {} in namespace {}", secretName, namespace)
    }

    companion object {
        private val log = LoggerFactory.getLogger(this::class.java)
        private val gson = Gson()

        /**
         * Checks whether a certificate needs renewal. Renewal can be caused either by certificate expiry
         * or if the list of domains requested for a SAN certificate changes.
         */
        fun certificateNeedsRenewal(domains: List<String>, secret: Secret): Boolean {
            val isExpiring = certificateIsExpiring(secret)
            val domainsChanged = haveDomainsChanged(domains, secret)

            return isExpiring || domainsChanged
        }

        fun certificateIsExpiring(secret: Secret): Boolean {
            val expiryDate = getExpiryDate(secret)

            if (expiryDate.isDefined()) {
                return LocalDate.now().isAfter(expiryDate.get().minusDays(2))
            } else {
                log.warn("No expiry date set on secret {} in namespace {}!",
                        secret.metadata.name, secret.metadata.namespace)
                return false
            }
        }

        private fun getExpiryDate(secret: Secret): Option<LocalDate> {
            val annotation = secret.metadata.annotations[EXPIRY_ANNOTATION]
            return annotation.toOption().map { LocalDate.parse(it) }
        }

        private fun haveDomainsChanged(domains: List<String>, secret: Secret): Boolean {
            return secret.metadata.annotations[REQUEST_ANNOTATION].toOption()
                    .map {
                        val type = object : TypeToken<List<String>>() {}.type
                        val secretDomains: List<String> = gson.fromJson(it, type)
                        (secretDomains.size != domains.size) || !secretDomains.containsAll(domains)
                    }
                    .getOrElse {
                        log.warn("acme/certificate annotation missing on secret {}!", secret.metadata.name)
                        false
                    }
        }
    }
}