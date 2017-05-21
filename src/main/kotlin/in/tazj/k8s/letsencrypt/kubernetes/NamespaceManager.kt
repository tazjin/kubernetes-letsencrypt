package `in`.tazj.k8s.letsencrypt.kubernetes

import `in`.tazj.k8s.letsencrypt.acme.CertificateRequestHandler
import io.fabric8.kubernetes.api.model.Namespace
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException
import io.fabric8.kubernetes.client.Watcher
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit.SECONDS


/**
 * Watches Kubernetes namespaces and starts / stops threads when they are added or deleted.
 *
 * Each thread is a reconciliation loop that checks all services in its target namespace.
 */
class NamespaceManager(
        private val client: KubernetesClient,
        private val secretManager: SecretManager,
        private val requestHandler: CertificateRequestHandler
) : Watcher<Namespace> {
    private val log = LoggerFactory.getLogger(this::class.java)
    private val namespaceExecutorMap = ConcurrentHashMap<String, ScheduledExecutorService>()

    override fun eventReceived(action: Watcher.Action, namespace: Namespace) {
        when (action) {
            Watcher.Action.ADDED -> handleAddedNamespace(namespace)
            Watcher.Action.DELETED -> handleDeletedNamespace(namespace)
            else -> log.debug("Received unhandled namespace event: {}", action.toString())
        }
    }

    private fun handleAddedNamespace(namespace: Namespace) {
        val name = namespace.metadata.name
        if (!namespaceExecutorMap.containsKey(name)) {
            log.info("Starting reconciliation loop for namespace {}", name)
            val serviceManager = ServiceManager(name, secretManager, requestHandler)
            val loop = ReconciliationLoop(name, serviceManager)
            val scheduler = Executors.newSingleThreadScheduledExecutor()

            scheduler.scheduleAtFixedRate(loop, 0, 45, SECONDS)
            namespaceExecutorMap.put(name, scheduler)
        }
    }

    private fun handleDeletedNamespace(namespace: Namespace) {
        val name = namespace.metadata.name
        if (namespaceExecutorMap.containsKey(name)) {
            log.info("Interrupting reconciliation loop for namespace {}", name)
            val scheduler = namespaceExecutorMap[name]
            scheduler?.shutdown()
        }
    }

    private inner class ReconciliationLoop(val namespace: String, val manager: ServiceManager)
        : Runnable {
        override fun run() {
            /* Run all existing services through the watcher */
            client.services().inNamespace(namespace).list().items
                    .forEach { manager.reconcileService(it) }
        }
    }


    override fun onClose(e: KubernetesClientException) {
        log.error("Lost connection to Kubernetes master: {}", e.message)
        System.exit(-1)
    }
}