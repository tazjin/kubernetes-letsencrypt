package in.tazj.k8s.letsencrypt.util;

import com.amazonaws.regions.Regions;

import org.xbill.DNS.Lookup;
import org.xbill.DNS.TextParseException;

import lombok.extern.slf4j.Slf4j;
import lombok.val;

/**
 * Inspects the environment to determine which cloud platform the controller is running on.
 *
 * Currently supported are Amazon Web Services and Google Cloud Platform.
 */
@Slf4j
public class DetectCloudPlatform {
  public enum CloudPlatform {
    GCP, AWS, UNKNOWN
  }

  public static CloudPlatform detectCloudPlatform() {
    log.info("Detecting current cloud platform ...");

    if (detectAmazonWebServices()) {
      log.info("Cloud platform is Amazon Web Services");
      return CloudPlatform.AWS;
    }

    if (detectGoogleCloudPlatform()) {
      log.info("Cloud platform is Google Cloud Platform");
      return CloudPlatform.GCP;
    }

    log.info("Could not detect cloud platform");
    return CloudPlatform.UNKNOWN;
  }

  /**
   * Attempts to figure out whether the environment is AWS by checking the current region.
   */
  private static boolean detectAmazonWebServices() {
    val region = Regions.getCurrentRegion();
    return (region != null);
  }

  /**
   * Attempts to figure out whether the environment is GCP by attempting to resolve the metadata
   * server host.
   */
  private static boolean detectGoogleCloudPlatform() {
    try {
      val lookup = new Lookup("metadata.google.internal");
      return lookup.run() != null;
    } catch (TextParseException e) {
      log.error("Error while detecting Google Cloud Platform: {}", e.getMessage());
      return false;
    }
  }
}
