package `in`.tazj.k8s.letsencrypt

import `in`.tazj.k8s.letsencrypt.acme.CertificateRequestHandler
import `in`.tazj.k8s.letsencrypt.acme.CloudDnsResponder
import `in`.tazj.k8s.letsencrypt.acme.DnsResponder
import `in`.tazj.k8s.letsencrypt.acme.Route53Responder
import `in`.tazj.k8s.letsencrypt.kubernetes.KeyPairManager
import `in`.tazj.k8s.letsencrypt.kubernetes.NamespaceManager
import `in`.tazj.k8s.letsencrypt.kubernetes.SecretManager
import `in`.tazj.k8s.letsencrypt.util.CloudPlatform.AWS
import `in`.tazj.k8s.letsencrypt.util.CloudPlatform.GCP
import `in`.tazj.k8s.letsencrypt.util.Configuration
import `in`.tazj.k8s.letsencrypt.util.LetsencryptException
import `in`.tazj.k8s.letsencrypt.util.loadConfiguration
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import com.amazonaws.services.route53.AmazonRoute53ClientBuilder
import com.google.cloud.dns.DnsOptions
import io.fabric8.kubernetes.client.DefaultKubernetesClient
import io.fabric8.kubernetes.client.Watcher
import org.funktionale.option.getOrElse
import org.funktionale.option.toOption
import org.slf4j.Logger.ROOT_LOGGER_NAME
import org.slf4j.LoggerFactory
import sun.misc.Signal

private val client = DefaultKubernetesClient()
private val log = LoggerFactory.getLogger("in.tazj.k8s.letsencrypt.Main")

fun main(args: Array<String>) {
    setLogLevel()
    registerSignalHandlers()

    val config = loadConfiguration()
    val dnsResponder = getCorrectDnsResponder(config)
    val keypairManager = KeyPairManager.with(client)
    val requestHandler = CertificateRequestHandler(
            config.acmeUrl, config.secretFilenames, keypairManager, dnsResponder
    )
    val secretManager = SecretManager(client)
    val namespaceManager = NamespaceManager(client, secretManager, requestHandler)

    // Add all currently existing namespaces to namespace manager
    client.namespaces().list().items.forEach(
            { namespaceManager.eventReceived(Watcher.Action.ADDED, it) }
    )

    // Start watching namespace events
    client.namespaces().watch(namespaceManager)
}

fun setLogLevel() {
    val level = System.getenv("LOG_LEVEL").toOption()
            .map { Level.valueOf(it) }
            .getOrElse { Level.INFO }

    val rootLogger = LoggerFactory.getLogger(ROOT_LOGGER_NAME) as Logger
    rootLogger.level = level
}

fun registerSignalHandlers() {
    val signalHandler = { signal: Signal ->
        log.info("Shutting down due to signal {}", signal)
        System.exit(0)
    }

    Signal.handle(Signal("INT"), signalHandler)
    Signal.handle(Signal("TERM"), signalHandler)
}

fun getCorrectDnsResponder(config: Configuration): DnsResponder {
    when (config.cloudPlatform) {
        GCP -> {
            val dns = DnsOptions.getDefaultInstance().service
            return CloudDnsResponder(dns)
        }
        AWS -> {
            val route53 = AmazonRoute53ClientBuilder.defaultClient()
            return Route53Responder(route53)
        }
        else -> {
            throw LetsencryptException("Could not determine correct DNS responder")
        }
    }
}