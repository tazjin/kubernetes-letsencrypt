package in.tazj.k8s.letsencrypt;

import com.google.common.collect.ImmutableMap;
import com.google.common.io.BaseEncoding;

import org.junit.Test;

import java.util.Optional;

import in.tazj.k8s.letsencrypt.util.DnsRecordObserver;
import io.fabric8.kubernetes.api.model.DoneableSecret;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DnsRecordObserverTest {
  @Test
  public void testDnsObserver() {
    final DnsRecordObserver observer =
        new DnsRecordObserver("acme-foo.tazj.in.", "tazj.in.", "\"expected\"");
    observer.observeDns();
  }

  @Test
  public void testSecretThing() {
    final Optional<Secret> secret =
        new DefaultKubernetesClient()
        .secrets()
            .list().getItems().stream()
            .filter(secret1 -> secret1.getMetadata().getName().equals("default-token-1cexr"))
            .findFirst();

    secret.ifPresent(secret1 -> log.info(secret1.toString()));
        /*.list()
        .getItems()
        .forEach(secret1 -> log.info(secret1.getMetadata().getName()));*/
  }

  @Test
  public void keypairtest() {
    final KubernetesClient client = new DefaultKubernetesClient();
    /* final KeyPairManager manager = KeyPairManager.with(client);

    log.info(manager.getKeyPair().toString()); */

    final DoneableSecret doneableSecret = client.secrets()
        .inNamespace("kube-system")
        .createNew();
    final Secret secret = doneableSecret.withNewMetadata()
        .withName("foo")
        .endMetadata()
        .withData(ImmutableMap.of("foo", BaseEncoding.base64().encode("bar".getBytes())))
        .done();

    log.info(secret.toString());
  }
}