package in.tazj.k8s.letsencrypt.acme;

import com.google.cloud.dns.ChangeRequest;
import com.google.cloud.dns.ChangeRequestInfo;
import com.google.cloud.dns.Dns;
import com.google.cloud.dns.RecordSet;
import com.google.cloud.dns.RecordSet.Type;
import com.google.cloud.dns.Zone;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;

import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import in.tazj.k8s.letsencrypt.util.LetsencryptException;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

import static com.google.cloud.dns.ChangeRequestInfo.Status.PENDING;
import static in.tazj.k8s.letsencrypt.acme.CloudDnsResponder.ChangeType.ADD;
import static in.tazj.k8s.letsencrypt.acme.CloudDnsResponder.ChangeType.REMOVE;
import static java.util.concurrent.TimeUnit.MINUTES;

/**
 * DNS challenge responder using Google Cloud DNS.
 */
@Slf4j @RequiredArgsConstructor
public class CloudDnsResponder implements DnsResponder {
  final private static long PROPAGATION_WAIT_SECONDS = 100;
  final private Dns dns;

  enum ChangeType {
    ADD, REMOVE
  }

  @Override
  public String addChallengeRecord(String recordName, String challengeDigest) {
    val result = updateCloudDnsRecord(recordName, challengeDigest, ADD);
    waitForUpdate(result);

    return dns.getZone(result.getZone()).getDnsName();
  }

  @Override
  public void removeChallengeRecord(String recordName, String challengeDigest) {
    updateCloudDnsRecord(recordName, challengeDigest, REMOVE);
  }

  @SneakyThrows
  private void waitForUpdate(ChangeRequest result) {
    log.info("Waiting for change in zone {} to finish. This may take some time.", result.getZone());
    while (result.status().equals(PENDING)) {
      Thread.sleep(500);
      result = dns.getChangeRequest(result.getZone(), result.getGeneratedId());
    }

    // Cloud DNS sometimes takes a while even after propagation has been confirmed.
    // The DNS observer queries all nameservers for the zone, however we sometimes see records still
    // being incorrect. Presumably something on Google's backend is eventually consistent.
    // With the below sleep timer the challenge will *usually* succeed. In some cases it may take a
    // few attempts.
    Thread.sleep(PROPAGATION_WAIT_SECONDS * 1000);
  }

  private ChangeRequest updateCloudDnsRecord(String recordName,
                                             String challengeDigest,
                                             ChangeType changeType) {
    val matchingZone = findMatchingZone(recordName);

    if (!matchingZone.isPresent()) {
      log.error("No matching zone found for {}", recordName);
      throw new LetsencryptException("No matching zone found.");
    }

    val zone = matchingZone.get();
    val fqdnRecord = determineFqdnRecord(recordName);

    val recordSet = RecordSet
        .newBuilder(fqdnRecord, Type.TXT)
        .setTtl(1, MINUTES)
        .addRecord(challengeDigest)
        .build();

    val changeBuilder = ChangeRequestInfo.newBuilder();

    if (changeType.equals(ADD)) {
      // Verify there is no existing record / overwrite it if there is.
      Iterator<RecordSet> recordSetIterator = zone.listRecordSets().iterateAll();
      while (recordSetIterator.hasNext()) {
        val current = recordSetIterator.next();
        if (recordSet.getName().equals(current.getName()) &&
            recordSet.getType().equals(current.getType())) {
          changeBuilder.delete(current);
        }
      }

      changeBuilder.add(recordSet);
    } else {
      changeBuilder.delete(recordSet);
    }

    return zone.applyChangeRequest(changeBuilder.build());
  }

  /**
   * Determine the most specific matching zone from available Cloud DNS zones.
   */
  @VisibleForTesting
  public Optional<Zone> findMatchingZone(String recordName) {
    val matchingZone = fetchMatchingZones(recordName).stream()
        .reduce(((acc, zone) -> {
          if (zone.getDnsName().length() > acc.getDnsName().length()) {
            return zone;
          }
          return acc;
        }));

    return matchingZone;
  }

  /**
   * Fetch all matching zones from Google Cloud DNS. This will deal with pagination accordingly.
   */
  private List<Zone> fetchMatchingZones(String recordName) {
    val fqdnRecord = determineFqdnRecord(recordName);
    val listBuilder = new ImmutableList.Builder<Zone>();

    dns.listZones().iterateAll().forEachRemaining(zone -> {
      if (fqdnRecord.contains(zone.getDnsName())) {
        listBuilder.add(zone);
      }
    });

    return listBuilder.build();
  }
}