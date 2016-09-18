package in.tazj.k8s.letsencrypt.kubernetes;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.BaseEncoding;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.internal.matchers.Any;
import org.mockito.runners.MockitoJUnitRunner;
import org.shredzone.acme4j.util.KeyPairUtils;

import java.io.IOException;
import java.io.StringWriter;
import java.util.List;

import io.fabric8.kubernetes.api.model.DoneableSecret;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.ClientMixedOperation;
import io.fabric8.kubernetes.client.dsl.ClientResource;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

@Slf4j
@RunWith(MockitoJUnitRunner.class)
public class KeyPairManagerTest {
  final static private BaseEncoding base64 = BaseEncoding.base64();

  @Mock
  public ClientMixedOperation<Secret, SecretList, DoneableSecret,
      ClientResource<Secret, DoneableSecret>> mockOperation;

  @Test
  public void testNoKeypairInCluster() {
    val client = getMockClient(ImmutableList.of());
    val keyPair = KeyPairManager.getKeyPairFromCluster(client);

    Assert.assertFalse("No keypair found in cluster", keyPair.isPresent());
  }

  @Test
  public void testKeyPairInCluster() throws IOException {
    // Prepare mock metadata
    val metadata = Mockito.mock(ObjectMeta.class);
    Mockito.when(metadata.getName()).thenReturn("letsencrypt-keypair");

    // Generate test keypair
    val keypair = KeyPairUtils.createKeyPair(2048);
    val writer = new StringWriter();
    KeyPairUtils.writeKeyPair(keypair, writer);

    // Prepare mock data
    val data = ImmutableMap.of("keypair", base64.encode(writer.toString().getBytes()));

    // Prepare mock secret
    val secret = Mockito.mock(Secret.class);
    Mockito.when(secret.getMetadata()).thenReturn(metadata);
    Mockito.when(secret.getData()).thenReturn(data);

    // Run test
    val client = getMockClient(ImmutableList.of(secret));
    val testKeyPair = KeyPairManager.getKeyPairFromCluster(client);

    Assert.assertTrue("Test key pair found", testKeyPair.isPresent());
    Assert.assertEquals("Test key pair decode correctly",
        keypair.getPublic(), testKeyPair.get().getPublic());
  }

  private KubernetesClient getMockClient(List<Secret> secretList) {
    val kubeClient = Mockito.mock(KubernetesClient.class);
    Mockito.when(kubeClient.secrets()).thenReturn(mockOperation);

    val testList = new SecretList();
    testList.setItems(secretList);
    Mockito.when(mockOperation.list()).thenReturn(testList);
    Mockito.when(mockOperation.inNamespace(Mockito.anyString())).thenReturn(mockOperation);

    return kubeClient;
  }

}