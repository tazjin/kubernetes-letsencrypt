package in.tazj.k8s.letsencrypt.acme;

/**
 * An interface that can be implemented by different DNS challenge responders.
 */
public interface DnsResponder {
  /** Receive a challenge and add it to the DNS provider.
   * Returns the root of the zone at which the record was inserted. */
  String addChallengeRecord(String recordName, String challengeContent);
}
