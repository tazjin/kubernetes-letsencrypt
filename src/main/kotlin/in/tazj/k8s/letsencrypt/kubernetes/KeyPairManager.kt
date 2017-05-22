package `in`.tazj.k8s.letsencrypt.kubernetes

import `in`.tazj.k8s.letsencrypt.model.SYSTEM_NAMESPACE
import com.google.common.io.BaseEncoding
import io.fabric8.kubernetes.client.KubernetesClient
import org.funktionale.option.Option
import org.funktionale.option.toOption
import org.shredzone.acme4j.util.KeyPairUtils
import org.slf4j.LoggerFactory
import java.io.StringReader
import java.io.StringWriter
import java.security.KeyPair

/**
 * Manages the Let's Encrypt user keypair as a secret in Kubernetes.
 */
class KeyPairManager(val keyPair: KeyPair) {
    companion object {
        val BASE64 = BaseEncoding.base64()
        val KEYPAIR_FIELD = "keypair"
        val SECRET_NAME = "letsencrypt-keypair"
        val log = LoggerFactory.getLogger(this::class.java)

        fun getKeyPairFromCluster(client: KubernetesClient): Option<KeyPair> {
            val secret = client.secrets().inNamespace(SYSTEM_NAMESPACE).list().items
                    .filter { it.metadata.name == SECRET_NAME }
                    .lastOrNull().toOption()

            return secret.map {
                val decodedKeyPair = String(BASE64.decode(it.data[KEYPAIR_FIELD]))
                val reader = StringReader(decodedKeyPair)
                return KeyPairUtils.readKeyPair(reader).toOption()
            }
        }

        fun with(client: KubernetesClient): KeyPairManager {
            val clusterKeyPair = getKeyPairFromCluster(client)

            if (clusterKeyPair.isDefined()) {
                log.info("Existing key pair loaded from cluster")
                return KeyPairManager(clusterKeyPair.get())
            } else {
                log.info("No existing key pair, generating a new one")
                val newKeyPair = KeyPairUtils.createKeyPair(2048)
                val manager = KeyPairManager(newKeyPair)
                manager.updateKeyPairInCluster(client)
                log.info("New key pair stored in cluster")
                return manager
            }
        }
    }

    private fun updateKeyPairInCluster(client: KubernetesClient) {
        val secretWriter = StringWriter()
        KeyPairUtils.writeKeyPair(keyPair, secretWriter)

        val encodedSecret = BASE64.encode(secretWriter.toString().toByteArray(charset("UTF-8")))
        val secretData = mapOf(KEYPAIR_FIELD to encodedSecret)

        client.secrets()
                .inNamespace(SYSTEM_NAMESPACE)
                .createNew()
                .withNewMetadata()
                .withName(SECRET_NAME)
                .endMetadata()
                .withData(secretData)
                .done()
    }
}