package `in`.tazj.k8s.letsencrypt.acme

import `in`.tazj.k8s.letsencrypt.util.LetsencryptException
import com.amazonaws.services.route53.AmazonRoute53
import com.amazonaws.services.route53.model.*
import com.amazonaws.services.route53.model.ChangeAction.DELETE
import com.amazonaws.services.route53.model.ChangeAction.UPSERT
import com.google.common.collect.ImmutableList
import org.funktionale.option.Option
import org.funktionale.option.toOption
import org.slf4j.LoggerFactory


/**
 * An ACME challenge responder using AWS Route53.
 */
class Route53Responder(val route53: AmazonRoute53) : DnsResponder {
    val log = LoggerFactory.getLogger(this::class.java)

    override fun addChallengeRecord(recordName: String, challengeDigest: String): String {
        return updateRoute53Record(recordName, challengeDigest, UPSERT).name
    }

    override fun removeChallengeRecord(recordName: String, challengeDigest: String) {
        updateRoute53Record(recordName, challengeDigest, DELETE)
    }

    @Synchronized
    private fun updateRoute53Record(recordName: String,
                                    challengeDigest: String,
                                    action: ChangeAction): HostedZone {
        val hostedZone = findHostedZone(recordName)

        if (hostedZone.isEmpty()) {
            log.error("No hosted zone found for record: {}!", recordName)
            throw LetsencryptException("No hosted zone found")
        }

        val recordValue = formatChallengeValue(challengeDigest)
        val recordSet = ResourceRecordSet(recordName, RRType.TXT)
                .withTTL(60L)
                .withResourceRecords(ResourceRecord(recordValue))
        val changeBatch = ChangeBatch(ImmutableList.of<Change>(
                Change(action, recordSet)))

        val request = ChangeResourceRecordSetsRequest(hostedZone.get().id, changeBatch)

        /* Commit change and wait until AWS confirms propagation */
        var changeInfo = route53.changeResourceRecordSets(request).changeInfo

        log.info("Applied {} to record {} in zone {} with value {}. Status: {}",
                action, recordName, hostedZone.get().name, recordValue, changeInfo.status)

        while (changeInfo.status == "PENDING") {
            changeInfo = route53.getChange(GetChangeRequest(changeInfo.id)).changeInfo
            Thread.sleep(1000)
        }

        return hostedZone.get()
    }

    /**
     * If no hosted zone is supplied explicitly this function will attempt to find a managed zone in
     * AWS that matches the supplied record.
     * If multiple matching zones are found the most specific one is chosen.
     */
    fun findHostedZone(record: String): Option<HostedZone> {
        val fqdnRecord = determineFqdnRecord(record)
        // Attempt to find the correct hosted zone by matching the longest matching suffix.
        val matchingZone = route53.listHostedZones().hostedZones
                .filter { fqdnRecord.endsWith(it.name) }
                .reduce { acc, zone ->
                    if (zone.name.length > acc.name.length) {
                        zone
                    } else if (zone.name == acc.name) {
                        log.debug("Both zones {} and {} are the same length, " +
                                "checking if private", zone.id, acc.id)
                        // If equal and 'zone' is not private, use that,
                        // else default back to 'acc' (normal behavior).
                        if( !zone.config.isPrivateZone) {
                            log.debug("Zone {} is public, using it", zone.id)
                            zone
                        } else {
                            acc
                        }
                    } else {
                        acc
                    }
                }.toOption()

        matchingZone.forEach { log.info("Found matching zone {} for record {}", it.name, record) }
        return matchingZone
    }

    // Route53 requires TXT record values to be explicitly enclosed in quotation marks.
    private fun formatChallengeValue(challenge: String): String {
        if (challenge.endsWith("\"")) {
            return challenge
        } else {
            return "\"" + challenge + "\""
        }
    }
}