package in.tazj.k8s.letsencrypt.acme;

import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.services.route53.AmazonRoute53;
import com.amazonaws.services.route53.AmazonRoute53Client;

import lombok.extern.slf4j.Slf4j;

/**
 * An ACME challenge responder using AWS Route53.
 */
@Slf4j
public class Route53Responder implements DnsResponder {
  final AmazonRoute53 route53 = new AmazonRoute53Client(new DefaultAWSCredentialsProviderChain());
  final String
  public void addRecord(String domain, String challenge) {

  }

  @Override
  public void addChallengeRecord(String recordName, String challengeText) {
    // final String challengeDomain = "_acme-challenge." + domain;
    log.info("Updated challenge for {}", recordName);
  }
}
