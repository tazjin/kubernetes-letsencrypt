package in.tazj.k8s.letsencrypt;

import in.tazj.k8s.letsencrypt.acme.CertificateRequestHandler;
import in.tazj.k8s.letsencrypt.acme.Route53Responder;
import in.tazj.k8s.letsencrypt.kubernetes.CertificateManager;
import in.tazj.k8s.letsencrypt.kubernetes.KeyPairManager;
import in.tazj.k8s.letsencrypt.kubernetes.ServiceWatcher;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watcher;
import lombok.extern.slf4j.Slf4j;

/**
 * Run the certificate controller and watch service objects.
 */
@Slf4j
public class Main {
  final static private KubernetesClient client = new DefaultKubernetesClient();

  public static void main(String[] args) {
    final CertificateManager certificateManager = new CertificateManager(client);
    final KeyPairManager keyPairManager = KeyPairManager.with(client);
    final String acmeUrl = "https://acme-staging.api.letsencrypt.org/directory";
    final CertificateRequestHandler requestHandler =
            new CertificateRequestHandler(acmeUrl, keyPairManager, new Route53Responder());

    final ServiceWatcher watcher = new ServiceWatcher(certificateManager, requestHandler);

    /* Start reconciliation loop */
    new Thread(() -> reconcile(watcher)).start();

    /* Start watching new service events */
    client.services().watch(watcher);
  }

  /** Run a reconciliation loop every two minutes. */
  public static void reconcile(ServiceWatcher watcher) {
    /* Run all existing services through the watcher */
    client.services().list().getItems().forEach(service ->
        watcher.eventReceived(Watcher.Action.ADDED, service));

    try {
      Thread.sleep(2 * 60 * 1000);
    } catch (InterruptedException e) {
      log.error("Reconciliation loop was interrupted. {}", e.getMessage());
      System.exit(-1);
    }

    reconcile(watcher);
  }
}
