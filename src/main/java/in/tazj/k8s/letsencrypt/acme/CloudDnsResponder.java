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
import java.util.concurrent.TimeUnit;

import in.tazj.k8s.letsencrypt.util.LetsencryptException;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

import static com.google.cloud.dns.ChangeRequestInfo.Status.PENDING;

/**
 * DNS challenge responder using Google Cloud DNS.
 */
@Slf4j
public class CloudDnsResponder implements DnsResponder {
  final private static long PROPAGATION_WAIT_SECONDS = 100;
  final private Dns dns;

  public CloudDnsResponder(Dns dns) {
    this.dns = dns;
  }


  @Override
  public String addChallengeRecord(String recordName, String challengeDigest) {
    val matchingZone = findMatchingZone(recordName);

    if (matchingZone.isPresent()) {
      val result = updateCloudDnsRecord(matchingZone.get(), recordName, challengeDigest);
      waitForUpdate(result);
      return matchingZone.get().dnsName();
    } else {
      log.error("No matching zone found for {}", recordName);
      throw new LetsencryptException("No matching zone found.");
    }
  }

  @SneakyThrows
  private void waitForUpdate(ChangeRequest result) {
    log.info("Waiting for change in zone {} to finish. This may take some time.", result.zone());
    while (result.status().equals(PENDING)) {
      Thread.sleep(500);
      result = dns.getChangeRequest(result.zone(), result.generatedId());
    }

    // Cloud DNS sometimes takes a while even after propagation has been confirmed.
    // The DNS observer queries all nameservers for the zone, however we sometimes see records still
    // being incorrect. Presumably something on Google's backend is eventually consistent.
    // With the below sleep timer the challenge will *usually* succeed. In some cases it may take a
    // few attempts.
    Thread.sleep(PROPAGATION_WAIT_SECONDS * 1000);
  }

  private ChangeRequest updateCloudDnsRecord(Zone zone, String recordName, String challengeDigest) {
    val fqdnRecord = determineFqdnRecord(recordName);
    val recordSet = RecordSet.builder(fqdnRecord, Type.TXT)
        .ttl(1, TimeUnit.MINUTES)
        .addRecord(challengeDigest)
        .build();
    val changeBuilder = ChangeRequestInfo.builder();

    // Verify there is no existing record / overwrite it if there is.
    Iterator<RecordSet> recordSetIterator = zone.listRecordSets().iterateAll();
    while (recordSetIterator.hasNext()) {
      RecordSet current = recordSetIterator.next();
      if (recordSet.name().equals(current.name()) &&
          recordSet.type().equals(current.type())) {
        changeBuilder.delete(current);
      }
    }

    changeBuilder.add(recordSet);

    return zone.applyChangeRequest(changeBuilder.build());
  }

  /**
   * Determine the most specific matching zone from available Cloud DNS zones.
   */
  @VisibleForTesting
  public Optional<Zone> findMatchingZone(String recordName) {
    val matchingZone = fetchMatchingZones(recordName).stream()
        .reduce(((acc, zone) -> {
          if (zone.dnsName().length() > acc.dnsName().length()) {
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
      if (fqdnRecord.contains(zone.dnsName())) {
        listBuilder.add(zone);
      }
    });

    return listBuilder.build();
  }
}