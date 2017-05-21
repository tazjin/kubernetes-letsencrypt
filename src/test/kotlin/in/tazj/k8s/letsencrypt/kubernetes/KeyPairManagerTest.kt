package `in`.tazj.k8s.letsencrypt.kubernetes

import com.google.common.collect.ImmutableMap
import com.google.common.io.BaseEncoding
import io.fabric8.kubernetes.api.model.DoneableSecret
import io.fabric8.kubernetes.api.model.ObjectMeta
import io.fabric8.kubernetes.api.model.Secret
import io.fabric8.kubernetes.api.model.SecretList
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.MixedOperation
import io.fabric8.kubernetes.client.dsl.Resource
import org.junit.Assert
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito
import org.shredzone.acme4j.util.KeyPairUtils
import java.io.StringWriter


class KeyPairManagerTest {
    private val BASE64 = BaseEncoding.base64()

    @Mock
    lateinit var mockOperation:
            MixedOperation<Secret, SecretList, DoneableSecret, Resource<Secret, DoneableSecret>>

    @Test
    fun testNoKeypairInCluster() {
        val client = getMockClient(listOf());
        val keyPair = KeyPairManager.getKeyPairFromCluster(client)

        Assert.assertFalse("No keypair found in cluster", keyPair.isDefined());
    }

    @Test
    fun testKeyPairInCluster() {
        // Prepare mock metadata
        val metadata = Mockito.mock(ObjectMeta::class.java)
        Mockito.`when`(metadata.name).thenReturn("letsencrypt-keypair")

        // Generate test keypair
        val keypair = KeyPairUtils.createKeyPair(2048)
        val writer = StringWriter()
        KeyPairUtils.writeKeyPair(keypair, writer)

        // Prepare mock data
        val data = ImmutableMap.of("keypair", BASE64.encode(writer.toString().toByteArray()))

        // Prepare mock secret
        val secret = Mockito.mock(Secret::class.java)
        Mockito.`when`(secret.metadata).thenReturn(metadata)
        Mockito.`when`(secret.data).thenReturn(data)

        // Run test
        val client = getMockClient(listOf(secret))
        val testKeyPair = KeyPairManager.getKeyPairFromCluster(client)

        Assert.assertTrue("Test key pair found", testKeyPair.isDefined())
        Assert.assertEquals("Test key pair decodes correctly",
                keypair.public, testKeyPair.get().public)
    }

    private fun getMockClient(secretList: List<Secret>): KubernetesClient {
        val kubeClient = Mockito.mock(KubernetesClient::class.java)
        Mockito.`when`(kubeClient.secrets()).thenReturn(mockOperation)

        val testList = SecretList()
        testList.items = secretList
        Mockito.`when`(mockOperation.list()).thenReturn(testList)
        Mockito.`when`(mockOperation.inNamespace(Mockito.anyString())).thenReturn(mockOperation)

        return kubeClient
    }
}