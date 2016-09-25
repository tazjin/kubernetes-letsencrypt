package in.tazj.k8s.letsencrypt.acme;

import com.google.common.collect.ImmutableMap;
import com.google.common.io.BaseEncoding;

import org.shredzone.acme4j.Authorization;
import org.shredzone.acme4j.Registration;
import org.shredzone.acme4j.RegistrationBuilder;
import org.shredzone.acme4j.Session;
import org.shredzone.acme4j.Status;
import org.shredzone.acme4j.challenge.Challenge;
import org.shredzone.acme4j.challenge.Dns01Challenge;
import org.shredzone.acme4j.exception.AcmeConflictException;
import org.shredzone.acme4j.exception.AcmeException;
import org.shredzone.acme4j.exception.AcmeUnauthorizedException;
import org.shredzone.acme4j.util.CSRBuilder;
import org.shredzone.acme4j.util.CertificateUtils;
import org.shredzone.acme4j.util.KeyPairUtils;

import java.io.IOException;
import java.io.StringWriter;
import java.util.List;

import in.tazj.k8s.letsencrypt.kubernetes.KeyPairManager;
import in.tazj.k8s.letsencrypt.model.CertificateResponse;
import in.tazj.k8s.letsencrypt.util.DnsRecordObserver;
import in.tazj.k8s.letsencrypt.util.LetsencryptException;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

/**
 * Requests certificates from a specified ACME server.
 */
@Slf4j
public class CertificateRequestHandler {
  final private BaseEncoding base64 = BaseEncoding.base64();
  final private String acmeServer;
  final private KeyPairManager keyPairManager;
  final private DnsResponder dnsResponder;

  public CertificateRequestHandler(String acmeServer, KeyPairManager keyPairManager, DnsResponder dnsResponder) {
    this.acmeServer = acmeServer;
    this.keyPairManager = keyPairManager;
    this.dnsResponder = dnsResponder;
  }

  public CertificateResponse requestCertificate(List<String> domains) {
    final Registration registration = getRegistration();

    try {
      // Complete domain challenges in parallel
      domains.parallelStream().forEach(domain -> authorizeDomain(registration, domain));
      return generateSignCertificate(domains, registration);
    } catch (AcmeUnauthorizedException e) {
      val agreementError = "Must agree to subscriber agreement before any further actions";
      if (e.getMessage().contains(agreementError)) {
        agreeToSubscriberLicense(registration);
        return requestCertificate(domains);
      } else {
        throw new LetsencryptException(e.getMessage());
      }

    } catch (AcmeException | IOException e) {
      e.printStackTrace();
      throw new LetsencryptException(e.getMessage());
    }
  }

  /**
   * Performs the authorization flow for a single domain after which the domain is authorized for
   * the given registration.
   * */
  private void authorizeDomain(Registration registration, String domain) {
    try {
      val authorization = registration.authorizeDomain(domain);
      val challenge = prepareDnsChallenge(authorization);
      completeChallenge(challenge);
    } catch (AcmeException e) {
      e.printStackTrace();
      throw new LetsencryptException(e.getMessage());
    }
  }

  private CertificateResponse generateSignCertificate(List<String> domains,
                                                      Registration registration)
      throws IOException, AcmeException {
    val domainKeyPair = KeyPairUtils.createKeyPair(2048);
    val csrBuilder = new CSRBuilder();
    domains.forEach(csrBuilder::addDomain);
    csrBuilder.sign(domainKeyPair);

    val certificate = registration.requestCertificate(csrBuilder.getEncoded());
    val downloadedCertificate = certificate.download();
    log.info("Successfully retrieved certificate for domains: {}", domains.toString());

    val certWriter = new StringWriter();
    CertificateUtils.writeX509Certificate(downloadedCertificate, certWriter);

    val chainWriter = new StringWriter();
    CertificateUtils.writeX509CertificateChain(certificate.downloadChain(), chainWriter);

    val keyWriter = new StringWriter();
    KeyPairUtils.writeKeyPair(domainKeyPair, keyWriter);

    val certificateFiles = ImmutableMap.of(
        "certificate.pem", base64EncodeWriter(certWriter),
        "chain.pem", base64EncodeWriter(chainWriter),
        "key.pem", base64EncodeWriter(keyWriter),
        "fullchain.pem", base64EncodeWriter(certWriter, chainWriter));

    return new CertificateResponse(domains, certificateFiles,
        downloadedCertificate.getNotAfter(), acmeServer);
  }

  @SneakyThrows // UnsupportedEncodingException can not be thrown for UTF-8
  private String base64EncodeWriter(StringWriter... writer) {
    String current = "";

    for (StringWriter stringWriter : writer) {
      current = current + stringWriter.toString();
    }

    return base64.encode(current.getBytes("UTF-8"));
  }

  /**
   * Attempt to validate the LetsEncrypt challenge with the retry policy specified above.
   * If the challenge does not complete within 10 minutes it is assumed to have failed and an
   * exception will be thrown.
   */
  @SneakyThrows // Ignore InterruptedException from sleep()
  private void completeChallenge(Challenge challenge) {
    challenge.trigger();
    challenge.update();

    while (challenge.getStatus().equals(Status.PENDING)) {
      challenge.update();
      Thread.sleep(100);
    }

    if (challenge.getStatus().equals(Status.INVALID)) {
      log.error("Challenge {} failed", challenge.getLocation());
      throw new LetsencryptException("Failed due to invalid challenge");
    }
  }

  /**
   * Creates a DNS challenge and calls the DNS responder with the challenge data.
   * Once this function returns the challenge, it should validate fine.
   */
  private Challenge prepareDnsChallenge(Authorization authorization) {
    final Dns01Challenge dns01Challenge = authorization.findChallenge(Dns01Challenge.TYPE);

    if (dns01Challenge == null) {
      log.error("Received no challenge from authorization for {}", authorization.getDomain());
      throw new LetsencryptException("Received no challenge");
    }

    final String challengeRecord = "_acme-challenge." + authorization.getDomain();
    final String rootZone =
        dnsResponder.addChallengeRecord(challengeRecord, dns01Challenge.getDigest());

    final DnsRecordObserver observer =
        new DnsRecordObserver(challengeRecord, rootZone, dns01Challenge.getDigest());
    observer.observeDns();

    return dns01Challenge;
  }

  private Registration getRegistration() {
    final Session session = new Session(acmeServer, keyPairManager.getKeyPair());
    Registration registration;

    try {
      registration = new RegistrationBuilder().create(session);
      agreeToSubscriberLicense(registration);
      log.info("Created new ACME user, URI: {}", registration.getLocation());
    } catch (AcmeConflictException e) {
      registration = Registration.bind(session, e.getLocation());
      log.info("Using existing ACME user: {}", registration.getLocation());
    } catch (AcmeException e) {
      log.error("Unexpected error while setting up registration: {}", e);
      throw new LetsencryptException(e.getMessage());
    }

    return registration;
  }

  private void agreeToSubscriberLicense(Registration registration) {
    val agreementUri = registration.getAgreement();
    log.info("Agreeing to Letsencrypt subscriber agreement. "
        + "Terms are available at {}", agreementUri);

    try {
      registration.modify().setAgreement(agreementUri).commit();
    } catch (AcmeException e) {
      log.error("Could not agree to new subscriber agreement: {}", e.getMessage());
      throw new LetsencryptException("Could not agree to subscriber agreement.");
    }
  }
}
