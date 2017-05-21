package `in`.tazj.k8s.letsencrypt.acme

/**
 * An interface that can be implemented by different DNS challenge responders.
 */
interface DnsResponder {
    /**
     * Receive a challenge and add it to the DNS provider.
     * Returns the root of the zone at which the record was inserted.
     */
    fun addChallengeRecord(recordName: String, challengeDigest: String): String

    /**
     * Removes a challenge record from DNS after challenge completion.
     */
    fun removeChallengeRecord(recordName: String, challengeDigest: String)

    /**
     * Determine whether a record is in FQDN format suffixed with a full stop, and add it otherwise.
     * Both Google Cloud DNS and AWS Route 53 use this format for zone DNS names.
     */
    fun determineFqdnRecord(record: String): String {
        if (record.endsWith(".")) {
            return record
        } else {
            return record + "."
        }
    }
}
