package `in`.tazj.k8s.letsencrypt.model

import java.util.*

/**
 * A signed certificate as returned by the ACME server.
 */
data class CertificateResponse(
        val domains: List<String>,
        val certificateFiles: Map<String, String>,
        val expiryDate: Date,
        val ca: String
)