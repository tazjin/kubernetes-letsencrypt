package in.tazj.k8s.letsencrypt.acme;

/**
 * An interface that can be implemented by different DNS challenge responders.
 */
public interface DnsResponder {
  /** Receive a challenge and add it to the DNS provider. */
  void addChallengeRecord(String recordName, String challengeContent);
}
