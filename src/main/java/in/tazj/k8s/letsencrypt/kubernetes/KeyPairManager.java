package in.tazj.k8s.letsencrypt.kubernetes;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.BaseEncoding;

import org.shredzone.acme4j.util.KeyPairUtils;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.security.KeyPair;
import java.util.Map;
import java.util.Optional;

import in.tazj.k8s.letsencrypt.util.LetsencryptException;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

/**
 * Manages the Letsencrypt user keypair as a secret in Kubernetes.
 */
@Slf4j
public class KeyPairManager {
  final static private String secretName = "letsencrypt-keypair"; // TODO: configurable
  final static private BaseEncoding base64 = BaseEncoding.base64();
  final private KeyPair keyPair;

  private KeyPairManager(KeyPair keyPair) {
    this.keyPair = keyPair;
  }

  public static KeyPairManager with(KubernetesClient client) {
    final Optional<KeyPair> clusterKeyPair = getKeyPairFromCluster(client);

    if (clusterKeyPair.isPresent()) {
      log.info("Existing key pair loaded from cluster");
      return new KeyPairManager(clusterKeyPair.get());
    } else {
      log.info("No existing key pair, generating a new one");
      final KeyPair newKeyPair = KeyPairUtils.createKeyPair(2048);
      final KeyPairManager manager = new KeyPairManager(newKeyPair);
      manager.updateKeyPairInCluster(client);
      log.info("New key pair stored in cluster");
      return manager;
    }
  }

  /** Update the newly generated key pair in the cluster. */
  @SneakyThrows
  private void updateKeyPairInCluster(KubernetesClient client) {
    final StringWriter secretWriter = new StringWriter();
    KeyPairUtils.writeKeyPair(keyPair, secretWriter);


    final String encodedSecret = base64.encode(secretWriter.toString().getBytes("UTF-8"));
    final Map<String, String> secretData =
        ImmutableMap.of("keypair", encodedSecret);

    client.secrets()
        .inNamespace("kube-system")
        .createNew()
        .withNewMetadata()
          .withName(secretName)
        .endMetadata()
        .withData(secretData)
        .done();
  }

  @VisibleForTesting
  public static Optional<KeyPair> getKeyPairFromCluster(KubernetesClient client) {
    final Optional<KeyPair> clusterKeyPair =
        client.secrets().inNamespace("kube-system").list().getItems().stream()
        .filter(secret -> secret.getMetadata().getName().equals(secretName))
        .map(secret -> {
          try {
            final String decodedKeyPair =
                new String(base64.decode(secret.getData().get("keypair")), "UTF-8");
            final StringReader reader = new StringReader(decodedKeyPair);

            return KeyPairUtils.readKeyPair(reader);
          } catch (IOException e) {
            throw new LetsencryptException("Could not decode keypair in cluster secret");
          }
        })
        .findAny();

    return clusterKeyPair;
  }

  public KeyPair getKeyPair() {
    return keyPair;
  }
}
