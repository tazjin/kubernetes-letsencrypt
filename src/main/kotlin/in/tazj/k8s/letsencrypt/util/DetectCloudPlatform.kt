package `in`.tazj.k8s.letsencrypt.util

import com.amazonaws.regions.Regions
import org.slf4j.LoggerFactory
import org.xbill.DNS.Lookup

enum class CloudPlatform {
    GCP, AWS, UNKNOWN
}

/**
 * Inspects the environment to determine which cloud platform the controller is running on.
 *
 * Currently supported are Amazon Web Services and Google Cloud Platform.
 */
fun detectCloudPlatform(): CloudPlatform {
    val log = LoggerFactory.getLogger(CloudPlatform::class.java)
    log.info("Detecting current cloud platform ...")
    return when {
        detectAmazonWebServices() -> {
            log.info("Cloud platform is Amazon Web Services")
            CloudPlatform.AWS
        }
        detectGoogleCloudPlatform() -> {
            log.info("Cloud platform is Google Cloud Platform")
            CloudPlatform.GCP
        }
        else -> {
            log.warn("Could not detect cloud platform")
            CloudPlatform.UNKNOWN
        }
    }
}

private fun detectAmazonWebServices(): Boolean {
    val region = Regions.getCurrentRegion()
    return (region != null)
}

private fun detectGoogleCloudPlatform(): Boolean {
    val lookup = Lookup("metadata.google.internal")
    return lookup.run() != null
}
