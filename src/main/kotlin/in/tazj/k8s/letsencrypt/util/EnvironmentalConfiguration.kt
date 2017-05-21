package `in`.tazj.k8s.letsencrypt.util

import org.funktionale.option.Option
import org.funktionale.option.getOrElse
import org.funktionale.option.toOption

data class SecretFilenames(
        val certificate: String,
        val chain: String,
        val key: String,
        val fullchain: String
)

data class Configuration(
        val cloudPlatform: CloudPlatform,
        val acmeUrl: String,
        val secretFilenames: SecretFilenames
)

private fun getOrDetectCloudPlatform(platform: Option<String>): CloudPlatform{
    return platform
            .map { CloudPlatform.valueOf(it) }
            .getOrElse { detectCloudPlatform() }
}

private fun getSecretFilenames(env: Map<String, String>): SecretFilenames {
    return SecretFilenames(
            env.getOrDefault("CERTIFICATE_FILENAME", "certificate.pem"),
            env.getOrDefault("CHAIN_FILENAME", "chain.pem"),
            env.getOrDefault("KEY_FILENAME", "key.pem"),
            env.getOrDefault("FULLCHAIN_FILENAME", "fullchain.pem")
    )
}

fun loadConfiguration(): Configuration {
    val env = System.getenv().toMap()
    return Configuration(
            getOrDetectCloudPlatform(env.get("CLOUD_PLATFORM").toOption()),
            env.getOrDefault("ACME_URL", "https://acme-v01.api.letsencrypt.org/directory"),
            getSecretFilenames(env)
    )
}
