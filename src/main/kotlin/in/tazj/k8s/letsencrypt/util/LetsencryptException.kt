package `in`.tazj.k8s.letsencrypt.util

/**
 * A runtime exception to be thrown if anything goes wrong during the Letsencrypt signing process.
 */
class LetsencryptException(msg: String? = null) : Exception(msg)
