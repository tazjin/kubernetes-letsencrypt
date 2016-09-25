package in.tazj.k8s.letsencrypt;

import com.google.cloud.dns.DnsOptions;

import com.amazonaws.services.route53.AmazonRoute53Client;

import in.tazj.k8s.letsencrypt.acme.CertificateRequestHandler;
import in.tazj.k8s.letsencrypt.acme.CloudDnsResponder;
import in.tazj.k8s.letsencrypt.acme.DnsResponder;
import in.tazj.k8s.letsencrypt.acme.Route53Responder;
import in.tazj.k8s.letsencrypt.kubernetes.SecretManager;
import in.tazj.k8s.letsencrypt.kubernetes.KeyPairManager;
import in.tazj.k8s.letsencrypt.kubernetes.NamespaceManager;
import in.tazj.k8s.letsencrypt.util.EnvironmentalConfiguration.Configuration;
import in.tazj.k8s.letsencrypt.util.LetsencryptException;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

import static in.tazj.k8s.letsencrypt.util.EnvironmentalConfiguration.loadConfiguration;
import static io.fabric8.kubernetes.client.Watcher.Action.ADDED;

/**
 * Run the certificate controller and watch service objects.
 */
@Slf4j
public class Main {
  final static private KubernetesClient client = new DefaultKubernetesClient();

  public static void main(String[] args) {
    val config = loadConfiguration();
    val dnsResponder = getCorrectDnsResponder(config);
    val certificateManager = new SecretManager(client);
    val keyPairManager = KeyPairManager.with(client);
    val requestHandler =
        new CertificateRequestHandler(config.getAcmeUrl(), keyPairManager, dnsResponder);
    val namespaceManager = new NamespaceManager(client, certificateManager, requestHandler);

    /* Add all currently existing services to namespace manager */
    client.namespaces().list().getItems()
        .forEach(namespace -> namespaceManager.eventReceived(ADDED, namespace));

    /* Start watching namespace events */
    client.namespaces().watch(namespaceManager);
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
