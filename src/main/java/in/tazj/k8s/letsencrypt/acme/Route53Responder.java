package in.tazj.k8s.letsencrypt.acme;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;

import com.amazonaws.services.route53.AmazonRoute53;
import com.amazonaws.services.route53.model.Change;
import com.amazonaws.services.route53.model.ChangeAction;
import com.amazonaws.services.route53.model.ChangeBatch;
import com.amazonaws.services.route53.model.ChangeResourceRecordSetsRequest;
import com.amazonaws.services.route53.model.GetChangeRequest;
import com.amazonaws.services.route53.model.HostedZone;
import com.amazonaws.services.route53.model.RRType;
import com.amazonaws.services.route53.model.ResourceRecord;
import com.amazonaws.services.route53.model.ResourceRecordSet;

import java.util.Optional;

import in.tazj.k8s.letsencrypt.util.LetsencryptException;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.Synchronized;
import lombok.experimental.var;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

import static com.amazonaws.services.route53.model.ChangeAction.DELETE;
import static com.amazonaws.services.route53.model.ChangeAction.UPSERT;

/**
 * An ACME challenge responder using AWS Route53.
 */
@Slf4j
@RequiredArgsConstructor
public class Route53Responder implements DnsResponder {
  final private AmazonRoute53 route53;

  @Override
  public String addChallengeRecord(String recordName, String challengeDigest) {
    return updateRoute53Record(recordName, challengeDigest, UPSERT).getName();
  }

  @Override
  public void removeChallengeRecord(String recordName, String challengeDigest) {
    updateRoute53Record(recordName, challengeDigest, DELETE);
  }

  @Synchronized
  @SneakyThrows // Ignore InterruptedException
  private HostedZone updateRoute53Record(String recordName,
                                         String challengeDigest,
                                         ChangeAction action) {
    val hostedZone = findHostedZone(recordName);

    if (!hostedZone.isPresent()) {
      log.error("No hosted zone found for record: {}!", recordName);
      throw new LetsencryptException("No hosted zone found");
    }

    val recordValue = formatChallengeValue(challengeDigest);
    val recordSet = new ResourceRecordSet(recordName, RRType.TXT)
        .withTTL(60L)
        .withResourceRecords(new ResourceRecord(recordValue));
    val changeBatch = new ChangeBatch(ImmutableList.of(
        new Change(action, recordSet)));

    val request =
        new ChangeResourceRecordSetsRequest(hostedZone.get().getId(), changeBatch);

    /* Commit change and wait until AWS confirms propagation */
    var changeInfo =
        route53.changeResourceRecordSets(request).getChangeInfo();

    log.info("Applied {} to record {} in zone {} with value {}. Status: {}",
        action, recordName, hostedZone.get().getName(), recordValue, changeInfo.getStatus());

    while (changeInfo.getStatus().equals("PENDING")) {
      changeInfo = route53.getChange(new GetChangeRequest(changeInfo.getId())).getChangeInfo();
      Thread.sleep(1000);
    }

    return hostedZone.get();
  }

  /**
   * If no hosted zone is supplied explicitly this function will attempt to find a managed zone in
   * AWS that matches the supplied record.
   * If multiple matching zones are found the most specific one is chosen.
   */
  @VisibleForTesting
  public Optional<HostedZone> findHostedZone(String record) {
    val fqdnRecord = determineFqdnRecord(record);
    // Attempt to find the correct hosted zone by matching the longest matching suffix.
    val matchingZone = route53.listHostedZones().getHostedZones().stream()
        .filter(zone -> fqdnRecord.endsWith(zone.getName()))
        .reduce(((acc, zone) -> {
          if (zone.getName().length() > acc.getName().length()) {
            return zone;
          }
          return acc;
        }));

    matchingZone.ifPresent(zone ->
        log.info("Found matching zone {} for record {}", zone.getName(), record));

    return matchingZone;
  }

  // Route53 requires TXT record values to be explicitly enclosed in quotation marks.
  private static String formatChallengeValue(String challenge) {
    if (challenge.endsWith("\"")) {
      return challenge;
    } else {
      return "\"" + challenge + "\"";
    }
  }
}