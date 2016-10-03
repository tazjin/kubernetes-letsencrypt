package in.tazj.k8s.letsencrypt.util;

import com.google.common.collect.ImmutableMap;

import java.util.Map;

import in.tazj.k8s.letsencrypt.util.DetectCloudPlatform.CloudPlatform;
import lombok.Value;
import lombok.val;

import static in.tazj.k8s.letsencrypt.util.DetectCloudPlatform.detectCloudPlatform;

/**
 * Retrieve all relevant configuration from the environment.
 */

public class EnvironmentalConfiguration {
  @Value
  public static class Configuration {
    CloudPlatform cloudPlatform;
    String acmeUrl;
    Map<String, String> secretFilenames;
  }

  public static Configuration loadConfiguration() {
    val environment = System.getenv();
    val acmeUrl =
        environment.getOrDefault("ACME_URL", "https://acme-v01.api.letsencrypt.org/directory");
    val cloudPlatform = getOrDetectCloudPlatform(environment);
    val secretFilenames = getSecretFilenames(environment);

    return new Configuration(cloudPlatform, acmeUrl, secretFilenames);
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

  private static ImmutableMap getSecretFilenames(Map<String, String> environment) {
    return ImmutableMap.of(
      "certificate", environment.getOrDefault("CERTIFICATE_FILENAME", "certificate.pem"),
      "chain", environment.getOrDefault("CHAIN_FILENAME", "chain.pem"),
      "key", environment.getOrDefault("KEY_FILENAME", "key.pem"),
      "fullchain", environment.getOrDefault("FULLCHAIN_FILENAME", "fullchain.pem"));
  }
}
