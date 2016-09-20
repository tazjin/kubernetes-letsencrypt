package in.tazj.k8s.letsencrypt.kubernetes;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import in.tazj.k8s.letsencrypt.acme.CertificateRequestHandler;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watcher;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

/**
 * Watches Kubernetes namespaces and starts / stops threads when they are added or deleted.
 *
 * Each thread is a reconciliation loop that checks all services in its target namespace.
 */
@Slf4j
public class NamespaceManager implements Watcher<Namespace> {
  final private KubernetesClient client;
  final private CertificateManager certificateManager;
  final private CertificateRequestHandler requestHandler;

  final private ConcurrentMap<String, Thread> namespaceThreadMap = new ConcurrentHashMap<>();

  public NamespaceManager(KubernetesClient client,
                          CertificateManager certificateManager,
                          CertificateRequestHandler requestHandler) {
    this.client = client;
    this.certificateManager = certificateManager;
    this.requestHandler = requestHandler;
  }

  @Override
  public void eventReceived(Action action, Namespace namespace) {
    switch (action) {
      case ADDED:
        handleAddedNamespace(namespace);
        break;
      case DELETED:
        handleDeletedNamespace(namespace);
        break;
      default:
        log.debug("Received unhandled namespace event: {}", action.toString());
        break;
    }
  }

  private void handleAddedNamespace(Namespace namespace) {
    val name = namespace.getMetadata().getName();
    if (!namespaceThreadMap.containsKey(name)) {
      log.info("Starting reconciliation loop for namespace {}", name);
      val serviceManager = new ServiceManager(name, certificateManager, requestHandler);
      val loop = new ReconciliationLoop(name, serviceManager);
      val thread = new Thread(loop);
      namespaceThreadMap.put(name, thread);
      thread.start();
    }
  }

  private void handleDeletedNamespace(Namespace namespace) {
    val name = namespace.getMetadata().getName();
    if (namespaceThreadMap.containsKey(name)) {
      log.info("Interrupting reconciliation loop for namespace {}", name);
      val thread = namespaceThreadMap.get(name);
      thread.interrupt();
    }
  }

  private class ReconciliationLoop implements Runnable {
    final private String namespace;
    final private ServiceManager manager;

    private ReconciliationLoop(String namespace, ServiceManager manager) {
      this.namespace = namespace;
      this.manager = manager;
    }

    @Override
    public void run() {
      /* Run all existing services through the watcher */
      client.services().inNamespace(namespace).list().getItems()
          .forEach(manager::reconcileService);

      try {
        Thread.sleep(60 * 1000);
      } catch (InterruptedException e) {
        log.error("Watcher thread for namespace {} ended", namespace);
      }

      run();
    }
  }

  @Override
  public void onClose(KubernetesClientException e) {
    log.error("Lost connection to Kubernetes master: {}", e.getMessage());
    System.exit(-1);
  }
}
