package `in`.tazj.k8s.letsencrypt.acme

import com.amazonaws.services.route53.AmazonRoute53Client
import com.amazonaws.services.route53.model.HostedZone
import com.amazonaws.services.route53.model.ListHostedZonesResult
import com.google.common.collect.ImmutableList
import org.junit.Assert
import org.junit.Test
import org.mockito.Mockito


class Route53ResponderTest {
    @Test
    fun correctHostedZoneIsFound() {
        val testList = ImmutableList.of(
                HostedZone("incorrect-1", "tazj.in.", ""),
                HostedZone("correct", "test.tazj.in.", ""),
                HostedZone("incorrect-2", "other.tazj.in.", "")
        )

        val client = Mockito.mock(AmazonRoute53Client::class.java)

        Mockito.`when`(client.listHostedZones())
                .thenReturn(ListHostedZonesResult().withHostedZones(testList))

        val responder = Route53Responder(client)
        val testRecord = "_acme-challenge.some.test.tazj.in"
        val result = responder.findHostedZone(testRecord)

        Assert.assertTrue("A hosted zone is found", result.isDefined())
        Assert.assertEquals("The selected hosted zone is correct", "correct", result.get().id)
    }
}