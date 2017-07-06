package `in`.tazj.k8s.letsencrypt.acme

import com.amazonaws.services.route53.AmazonRoute53Client
import com.amazonaws.services.route53.model.HostedZone
import com.amazonaws.services.route53.model.HostedZoneConfig
import com.amazonaws.services.route53.model.ListHostedZonesResult
import com.google.common.collect.ImmutableList
import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockito_kotlin.mock
import org.junit.Assert
import org.junit.Test

class Route53ResponderTest {
    @Test
    fun correctHostedZoneIsFound() {
        val testList = ImmutableList.of(
                HostedZone("incorrect-1", "tazj.in.", "")
                        .withConfig(HostedZoneConfig().withPrivateZone(false)),
                HostedZone("correct", "test.tazj.in.", "")
                        .withConfig(HostedZoneConfig().withPrivateZone(false)),
                HostedZone("incorrect-2", "other.tazj.in.", "")
                        .withConfig(HostedZoneConfig().withPrivateZone(false))
        )

        val client: AmazonRoute53Client = mock {
            on { listHostedZones() } doReturn (ListHostedZonesResult().withHostedZones(testList))
        }

        val responder = Route53Responder(client)
        val testRecord = "_acme-challenge.some.test.tazj.in"
        val result = responder.findHostedZone(testRecord)

        Assert.assertTrue("A hosted zone is found", result.isDefined())
        Assert.assertEquals("The selected hosted zone is correct", "correct", result.get().id)
    }

    @Test
    fun correctPublicHostedZoneIsFound() {
        val testList = ImmutableList.of(
                HostedZone("incorrect-1", "tazj.in.", "")
                        .withConfig(HostedZoneConfig().withPrivateZone(true)),
                HostedZone("private", "test.tazj.in.", "")
                        .withConfig(HostedZoneConfig().withPrivateZone(true)),
                HostedZone("public", "test.tazj.in.", "")
                        .withConfig(HostedZoneConfig().withPrivateZone(false)),
                HostedZone("incorrect-2", "other.tazj.in.", "")
                        .withConfig(HostedZoneConfig().withPrivateZone(true))
        )

        val client: AmazonRoute53Client = mock {
            on { listHostedZones() } doReturn (ListHostedZonesResult().withHostedZones(testList))
        }

        val responder = Route53Responder(client)
        val testRecord = "_acme-challenge.some.test.tazj.in"
        val result = responder.findHostedZone(testRecord)

        Assert.assertTrue("A hosted zone is found", result.isDefined())
        Assert.assertEquals("The selected hosted zone is correct", "public", result.get().id)
    }
}