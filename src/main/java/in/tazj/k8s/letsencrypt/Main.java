package in.tazj.k8s.letsencrypt;

import com.google.cloud.dns.DnsOptions;

import com.amazonaws.services.route53.AmazonRoute53Client;

import in.tazj.k8s.letsencrypt.acme.CertificateRequestHandler;
import in.tazj.k8s.letsencrypt.acme.CloudDnsResponder;
import in.tazj.k8s.letsencrypt.acme.DnsResponder;
import in.tazj.k8s.letsencrypt.acme.Route53Responder;
import in.tazj.k8s.letsencrypt.kubernetes.CertificateManager;
import in.tazj.k8s.letsencrypt.kubernetes.KeyPairManager;
import in.tazj.k8s.letsencrypt.kubernetes.ServiceWatcher;
import in.tazj.k8s.letsencrypt.util.EnvironmentalConfiguration.Configuration;
import in.tazj.k8s.letsencrypt.util.LetsencryptException;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watcher;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

import static in.tazj.k8s.letsencrypt.util.EnvironmentalConfiguration.loadConfiguration;

/**
 * Run the certificate controller and watch service objects.
 */
@Slf4j
public class Main {
  final static private KubernetesClient client = new DefaultKubernetesClient();

  public static void main(String[] args) {
    final Configuration configuration = loadConfiguration();
    final DnsResponder dnsResponder = getCorrectDnsResponder(configuration);
    final CertificateManager certificateManager = new CertificateManager(client);
    final KeyPairManager keyPairManager = KeyPairManager.with(client);
    final CertificateRequestHandler requestHandler =
        new CertificateRequestHandler(configuration.getAcmeUrl(), keyPairManager, dnsResponder);

    final ServiceWatcher watcher = new ServiceWatcher(certificateManager, requestHandler);

    /* Start reconciliation loop */
    new Thread(() -> reconcile(watcher)).start();

    /* Start watching new service events */
    client.services().watch(watcher);
  }

  /** Run a reconciliation loop every five minutes. */
  public static void reconcile(ServiceWatcher watcher) {
    /* Run all existing services through the watcher */
    client.services().list().getItems().forEach(service ->
        watcher.eventReceived(Watcher.Action.ADDED, service));

    try {
      Thread.sleep(5 * 60 * 1000);
    } catch (InterruptedException e) {
      log.error("Reconciliation loop was interrupted. {}", e.getMessage());
      System.exit(-1);
    }

    reconcile(watcher);
  }

  /* Detects the correct cloud platform and returns an appropriate DNS responder. */
  private static DnsResponder getCorrectDnsResponder(Configuration configuration) {
    switch (configuration.getCloudPlatform()) {
      case GCP:
        val dns = DnsOptions.defaultInstance().service();
        return new CloudDnsResponder(dns);
      case AWS:
        val route53 = new AmazonRoute53Client();
        return new Route53Responder(route53);
      default:
        throw new LetsencryptException("Could not determine correct DNS responder");
    }
  }
}
