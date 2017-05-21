package `in`.tazj.k8s.letsencrypt.acme


import com.google.cloud.Page
import com.google.cloud.dns.Dns
import com.google.cloud.dns.Zone
import com.google.common.collect.ImmutableList
import org.junit.Assert
import org.junit.Test
import org.mockito.Mockito


class CloudDnsResponderTest {
    @Test
    fun testFindCorrectZone() {
        val dns = Mockito.mock(Dns::class.java)
        val page = Mockito.mock(Page::class.java)
        val iterator = ImmutableList.of(
                zoneOf("incorrect-1", "tazj.in."),
                zoneOf("correct", "test.tazj.in."),
                zoneOf("incorrect-2", "other.tazj.in.")
        ).iterator()

        Mockito.`when`(page.iterateAll()).thenReturn(iterator)

        Mockito.`when`(dns.listZones()).thenReturn(page as Page<Zone>)

        val responder = CloudDnsResponder(dns)
        val testRecord = "_acme-challenge.some.test.tazj.in"

        val result = responder.findMatchingZone(testRecord)

        Assert.assertTrue("A matching zone should be found", result.isDefined())
        Assert.assertEquals("Correct zone is found", "correct", result.get().name)
    }

    private fun zoneOf(name: String, dnsName: String): Zone {
        val zone = Mockito.mock(Zone::class.java)
        Mockito.`when`(zone.name).thenReturn(name)
        Mockito.`when`(zone.dnsName).thenReturn(dnsName)
        return zone
    }
}