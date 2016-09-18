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
  public static void main(String[] args) {
    final KubernetesClient client = new DefaultKubernetesClient();
    final CertificateManager certificateManager = new CertificateManager(client);
    final KeyPairManager keyPairManager = KeyPairManager.with(client);
    final String acmeUrl = "https://acme-v01.api.letsencrypt.org/directory";

    final CertificateRequestHandler requestHandler =
            new CertificateRequestHandler(acmeUrl, keyPairManager, new Route53Responder());

    final ServiceWatcher watcher = new ServiceWatcher(certificateManager, requestHandler);

    /* Run all existing services through the watcher */
    client.services().list().getItems().forEach(service ->
        watcher.eventReceived(Watcher.Action.ADDED, service));

    /* Start watching new service events */
    client.services().watch(watcher);
  }
}
