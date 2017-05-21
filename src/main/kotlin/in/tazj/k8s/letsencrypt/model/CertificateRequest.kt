package `in`.tazj.k8s.letsencrypt.model

/**
 * A request for a signed certificate based upon service annotations.
 */
data class CertificateRequest(
        /** The name of the Kubernetes secret to store the certificate in. */
        val secretName: String,

        /** The certificate subject name. */
        val domains: List<String>,

        /** Whether the certificate needs to be renewed. */
        val renew: Boolean
)