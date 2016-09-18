package in.tazj.k8s.letsencrypt.acme;

import com.google.common.collect.ImmutableList;

import com.amazonaws.services.route53.AmazonRoute53Client;
import com.amazonaws.services.route53.model.HostedZone;
import com.amazonaws.services.route53.model.ListHostedZonesResult;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

import lombok.val;

public class Route53ResponderTest {
  @Test /* Test that the correct hosted zone is selected if multiple zones match */
  public void findHostedZone() throws Exception {
    val testList = ImmutableList.of(
        new HostedZone("incorrect-1", "tazj.in.", ""),
        new HostedZone("correct", "test.tazj.in.", ""),
        new HostedZone("incorrect-2", "other.tazj.in.", "")
    );

    val client = Mockito.mock(AmazonRoute53Client.class);

    Mockito
        .when(client.listHostedZones())
        .thenReturn(new ListHostedZonesResult().withHostedZones(testList));

    val responder = new Route53Responder(client);
    val testRecord = "_acme-challenge.some.test.tazj.in";
    val result = responder.findHostedZone(testRecord);

    Assert.assertTrue("A hosted zone is found", result.isPresent());
    Assert.assertEquals("The selected hosted zone is correct", "correct", result.get().getId());
  }
}