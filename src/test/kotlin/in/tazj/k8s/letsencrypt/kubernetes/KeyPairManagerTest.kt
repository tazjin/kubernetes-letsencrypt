package `in`.tazj.k8s.letsencrypt.kubernetes

import com.google.common.collect.ImmutableMap
import com.google.common.io.BaseEncoding
import com.nhaarman.mockito_kotlin.*
import io.fabric8.kubernetes.api.model.DoneableSecret
import io.fabric8.kubernetes.api.model.ObjectMeta
import io.fabric8.kubernetes.api.model.Secret
import io.fabric8.kubernetes.api.model.SecretList
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.MixedOperation
import io.fabric8.kubernetes.client.dsl.Resource
import org.junit.Assert
import org.junit.Test
import org.shredzone.acme4j.util.KeyPairUtils
import java.io.StringWriter


class KeyPairManagerTest {
    private val BASE64 = BaseEncoding.base64()

    val mockOperation: MixedOperation<Secret, SecretList, DoneableSecret,
            Resource<Secret, DoneableSecret>> = mock()

    val client: KubernetesClient

    init {
        client = mock {
            on { secrets() } doReturn mockOperation
        }
    }

    @Test
    fun testNoKeypairInCluster() {
        prepareMockOperation(listOf())
        val keyPair = KeyPairManager.getKeyPairFromCluster(client)

        Assert.assertFalse("No keypair found in cluster", keyPair.isDefined());
    }

    @Test
    fun testKeyPairInCluster() {
        // Prepare mock metadata
        val metadata: ObjectMeta = mock {
            on { name } doReturn ("letsencrypt-keypair")
        }

        // Generate test keypair
        val keypair = KeyPairUtils.createKeyPair(2048)
        val writer = StringWriter()
        KeyPairUtils.writeKeyPair(keypair, writer)

        // Prepare mock data
        val data = ImmutableMap.of("keypair", BASE64.encode(writer.toString().toByteArray()))

        // Prepare mock secret
        val secret: Secret = mock {
            on { getMetadata() } doReturn (metadata)
            on { getData() } doReturn (data)
        }
        prepareMockOperation(listOf(secret))

        // Run test
        val testKeyPair = KeyPairManager.getKeyPairFromCluster(client)

        Assert.assertTrue("Test key pair found", testKeyPair.isDefined())
        Assert.assertEquals("Test key pair decodes correctly",
                keypair.public, testKeyPair.get().public)
    }

    private fun prepareMockOperation(secretList: List<Secret>) {
        reset(mockOperation)
        val testList = SecretList()
        testList.items = secretList
        whenever(mockOperation.list()).thenReturn(testList)
        whenever(mockOperation.inNamespace(any())).thenReturn(mockOperation)
    }
}