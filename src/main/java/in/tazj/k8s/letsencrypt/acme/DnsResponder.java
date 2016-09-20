package in.tazj.k8s.letsencrypt.acme;

/**
 * An interface that can be implemented by different DNS challenge responders.
 */
public interface DnsResponder {
  /**
   * Receive a challenge and add it to the DNS provider.
   * Returns the root of the zone at which the record was inserted.
   */
  String addChallengeRecord(String recordName, String challengeDigest);

  /**
   * Determine whether a record is in FQDN format suffixed with a full stop, and add it otherwise.
   * Both Google Cloud DNS and AWS Route 53 use this format for zone DNS names.
   */
  default String determineFqdnRecord(String record) {
    if (record.endsWith(".")) {
      return record;
    } else {
      return record + ".";
    }
  }
}
