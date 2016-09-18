package in.tazj.k8s.letsencrypt.util;

import java.util.Map;

import in.tazj.k8s.letsencrypt.util.DetectCloudPlatform.CloudPlatform;
import lombok.Value;
import lombok.val;

import static in.tazj.k8s.letsencrypt.util.DetectCloudPlatform.detectCloudPlatform;

/**
 * Retrieve all relevant configuration from the environment.
 */

public class EnvironmentalConfiguration {
  @Value public static class Configuration {
    CloudPlatform cloudPlatform;
    String acmeUrl;
  }

  public static Configuration loadConfiguration() {
    val environment = System.getenv();
    val acmeUrl =
        environment.getOrDefault("ACME_URL", "https://acme-v01.api.letsencrypt.org/directory");

    val cloudPlatform = detectCloudPlatform();

    return new Configuration(cloudPlatform, acmeUrl);
  }

  private static CloudPlatform getOrDetectCloudPlatform(Map<String, String> environment) {
    if (environment.containsKey("CLOUD_PLATFORM")) {
      switch (environment.get("CLOUD_PLATFORM")) {
        case "GCP":
          return CloudPlatform.GCP;
        case "AWS":
          return CloudPlatform.AWS;
        default:
          return detectCloudPlatform();
      }
    }

    return detectCloudPlatform();
  }
}
