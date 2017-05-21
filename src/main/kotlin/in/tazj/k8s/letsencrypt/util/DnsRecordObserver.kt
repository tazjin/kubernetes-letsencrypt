package `in`.tazj.k8s.letsencrypt.util

import com.google.common.collect.ImmutableList
import org.joda.time.DateTime
import org.joda.time.Minutes
import org.slf4j.LoggerFactory
import org.xbill.DNS.*
import java.net.UnknownHostException

/**
 * This class watches a given DNS record with an expected value and waits until the record has
 * propagated to the root nameservers for the domain.
 */
class DnsRecordObserver(private val recordName: String, private val rootZone: String, private val expectedValue: String) {
    private val log = LoggerFactory.getLogger(this.javaClass)

    // TODO: Configurable!
    private val observerTimeout = Minutes.minutes(1)
    private val dateTime = DateTime()

    fun observeDns() {
        log.info("Waiting for DNS record '{}' update", recordName)
        try {
            val nameservers = findAuthoritativeNameservers()

            // Wait on all nameservers in parallel
            nameservers.parallelStream()
                    .map({ it.rdataToString() })
                    .forEach({ this.waitWithNameserver(it) })

        } catch (e: TextParseException) {
            e.printStackTrace()
        }

    }

    private fun waitWithNameserver(nameserver: String) {
        try {
            val lookup = Lookup(recordName, Type.TXT)
            lookup.setResolver(SimpleResolver(nameserver))

            while (dateTime.plus(observerTimeout).isAfterNow) {
                log.debug("Looking up {} in {}", recordName, nameserver)
                lookup.setCache(Cache())

                val records = lookup.run()

                if (records != null && records[0].rdataToString().contains(expectedValue)) {
                    log.info("Record {} updated in {}", recordName, nameserver)
                    return
                }

                if (records != null) {
                    log.debug("Current value in {}: {}, expected: {}",
                            nameserver, records[0].rdataToString(), expectedValue)
                }

                Thread.sleep(1000)
            }
            throw LetsencryptException("Timeout while waiting for record '"
                    + recordName + "' to update.")
        } catch (e: TextParseException) {
            throw LetsencryptException(e.message)
        } catch (e: UnknownHostException) {
            throw LetsencryptException(e.message)
        } catch (e: InterruptedException) {
            throw LetsencryptException(e.message)
        }

    }

    @Throws(TextParseException::class)
    private fun findAuthoritativeNameservers(): List<Record> {
        val lookup = Lookup(rootZone, Type.NS)
        val records = lookup.run()
        return ImmutableList.copyOf(records)
    }
}
