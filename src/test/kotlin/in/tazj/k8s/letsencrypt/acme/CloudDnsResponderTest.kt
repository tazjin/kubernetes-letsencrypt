package `in`.tazj.k8s.letsencrypt.acme

import com.google.cloud.Page
import com.google.cloud.dns.Dns
import com.google.cloud.dns.Zone
import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockito_kotlin.mock
import org.junit.Assert
import org.junit.Test


class CloudDnsResponderTest {
    @Test
    fun testFindCorrectZone() {
        val iterator = listOf(
                zoneOf("incorrect-1", "tazj.in."),
                zoneOf("correct", "test.tazj.in."),
                zoneOf("incorrect-2", "other.tazj.in.")
        ).iterator()

        val page: Page<Zone> = mock {
            on { iterateAll() } doReturn (iterator)
        }

        val dns: Dns = mock {
            on { listZones() } doReturn (page)
        }

        val responder = CloudDnsResponder(dns)
        val testRecord = "_acme-challenge.some.test.tazj.in"

        val result = responder.findMatchingZone(testRecord)

        Assert.assertTrue("A matching zone should be found", result.isDefined())
        Assert.assertEquals("Correct zone is found", "correct", result.get().name)
    }

    private fun zoneOf(name: String, dnsName: String): Zone {
        return mock {
            on { getName() } doReturn (name)
            on { getDnsName() } doReturn (dnsName)
        }
    }
}