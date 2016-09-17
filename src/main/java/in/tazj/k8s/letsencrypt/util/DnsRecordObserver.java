package in.tazj.k8s.letsencrypt.util;

import com.google.common.collect.ImmutableList;

import org.joda.time.DateTime;
import org.joda.time.Minutes;
import org.xbill.DNS.Cache;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.Record;
import org.xbill.DNS.SimpleResolver;
import org.xbill.DNS.TextParseException;
import org.xbill.DNS.Type;

import java.net.UnknownHostException;
import java.util.List;

import lombok.extern.slf4j.Slf4j;

/**
 * This class watches a given DNS record with an expected value and waits until the record has
 * propagated to the root nameservers for the domain.
 */
@Slf4j
public class DnsRecordObserver {
  // TODO: Configurable!
  final Minutes observerTimeout = Minutes.minutes(15);
  final DateTime dateTime = new DateTime();
  final String recordName;
  final String rootZone;
  final String expectedValue;

  public DnsRecordObserver(String recordName, String rootZone, String expectedValue) {
    this.recordName = recordName;
    this.rootZone = rootZone;
    this.expectedValue = expectedValue;
  }

  public void observeDns() {
    log.info("Waiting for DNS record '{}' update", recordName);
    try {
      final List<Record> nameservers = findAuthoritativeNameservers();

      // Wait on all nameservers in parallel
      nameservers.parallelStream()
          .map(record -> record.rdataToString())
          .forEach(nameserver -> waitWithNameserver(nameserver));
    } catch (TextParseException e) {
      e.printStackTrace();
    }
  }

  private void waitWithNameserver(String nameserver) {
    try {
      final Lookup lookup = new Lookup(recordName, Type.TXT);
      lookup.setResolver(new SimpleResolver(nameserver));

      while (dateTime.plus(observerTimeout).isAfterNow()) {
        log.info("Looking up {} in {}", recordName, nameserver);
        lookup.setCache(new Cache());

        final Record[] records = lookup.run();

        if (records != null && records[0].rdataToString().equals(expectedValue)) {
          log.info("Record {} updated in {}", recordName, nameserver);
          return;
        }

        if (records != null) {
          log.debug("Current value in {}: {}, expected: {}",
              nameserver, records[0].rdataToString(), expectedValue);
        }

        Thread.sleep(1000);
      }
      throw new LetsencryptException("Timeout while waiting for record '"
          + recordName + "' to update.");
    } catch (TextParseException | UnknownHostException | InterruptedException e) {
      throw new LetsencryptException(e.getMessage());
    }
  }

  private List<Record> findAuthoritativeNameservers() throws TextParseException {
    final Lookup lookup = new Lookup(rootZone, Type.NS);
    final Record[] records = lookup.run();
    return ImmutableList.copyOf(records);
  }
}
