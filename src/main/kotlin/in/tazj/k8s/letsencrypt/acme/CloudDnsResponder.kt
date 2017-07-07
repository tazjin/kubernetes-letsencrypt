package `in`.tazj.k8s.letsencrypt.acme

import `in`.tazj.k8s.letsencrypt.acme.CloudDnsResponder.ChangeType.ADD
import `in`.tazj.k8s.letsencrypt.acme.CloudDnsResponder.ChangeType.REMOVE
import `in`.tazj.k8s.letsencrypt.util.LetsencryptException
import com.google.cloud.dns.*
import com.google.cloud.dns.ChangeRequestInfo.Status.PENDING
import org.funktionale.option.Option
import org.funktionale.option.toOption
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit.MINUTES


/**
 * DNS challenge responder using Google Cloud DNS.
 */
class CloudDnsResponder(private val dns: Dns) : DnsResponder {
    private val log = LoggerFactory.getLogger(this::class.java)
    private val PROPAGATION_WAIT_SECONDS: Long = 100

    internal enum class ChangeType {
        ADD, REMOVE
    }

    override fun addChallengeRecord(recordName: String, challengeDigest: String): String {
        val result = updateCloudDnsRecord(recordName, challengeDigest, ADD)
        waitForUpdate(result)

        return dns.getZone(result.zone).dnsName
    }

    override fun removeChallengeRecord(recordName: String, challengeDigest: String) {
        updateCloudDnsRecord(recordName, challengeDigest, REMOVE)
    }

    private fun waitForUpdate(result: ChangeRequest) {
        var modifiableResult = result
        log.info("Waiting for change in zone {} to finish. This may take some time.", result.zone)
        while (modifiableResult.status() == PENDING) {
            Thread.sleep(500)
            modifiableResult = dns.getChangeRequest(result.zone, result.generatedId)
        }

        // Cloud DNS sometimes takes a while even after propagation has been confirmed.
        // The DNS observer queries all nameservers for the zone, however we sometimes see records still
        // being incorrect. Presumably something on Google's backend is eventually consistent.
        // With the below sleep timer the challenge will *usually* succeed. In some cases it may take a
        // few attempts.
        Thread.sleep(PROPAGATION_WAIT_SECONDS * 1000)
    }

    private fun updateCloudDnsRecord(recordName: String,
                                     challengeDigest: String,
                                     changeType: ChangeType): ChangeRequest {
        val matchingZone = findMatchingZone(recordName)

        if (matchingZone.isEmpty()) {
            log.error("No matching zone found for {}", recordName)
            throw LetsencryptException("No matching zone found.")
        }

        val zone = matchingZone.get()
        val fqdnRecord = determineFqdnRecord(recordName)

        val recordSet = RecordSet
                .newBuilder(fqdnRecord, RecordSet.Type.TXT)
                .setTtl(1, MINUTES)
                .addRecord(challengeDigest)
                .build()

        val changeBuilder = ChangeRequestInfo.newBuilder()

        if (changeType == ADD) {
            // Verify there is no existing record / overwrite it if there is.
            val recordSetIterator = zone.listRecordSets().iterateAll()
            while (recordSetIterator.hasNext()) {
                val current = recordSetIterator.next()
                if (recordSet.name == current.name && recordSet.type == current.type) {
                    changeBuilder.delete(current)
                }
            }

            changeBuilder.add(recordSet)
        } else {
            changeBuilder.delete(recordSet)
        }

        return zone.applyChangeRequest(changeBuilder.build())
    }

    /**
     * Determine the most specific matching zone from available Cloud DNS zones.
     */
    fun findMatchingZone(recordName: String): Option<Zone> {
        return fetchMatchingZones(recordName)
                .reduce { acc, zone ->
                    if (zone.dnsName.length > acc.dnsName.length) {
                        zone
                    } else {
                        acc
                    }
                }.toOption()
    }

    /**
     * Fetch all matching zones from Google Cloud DNS.
     */
    private fun fetchMatchingZones(recordName: String): List<Zone> {
        val fqdnRecord = determineFqdnRecord(recordName)
        var zoneList: List<Zone> = mutableListOf()

        dns.listZones().iterateAll().forEachRemaining { zone ->
            if (fqdnRecord.contains(zone.dnsName)) {
                zoneList = zoneList.plus(zone)
            }
        }

        return zoneList
    }
}
