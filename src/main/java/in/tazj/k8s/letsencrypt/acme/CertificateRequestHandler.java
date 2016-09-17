package in.tazj.k8s.letsencrypt.acme;

import com.google.common.collect.ImmutableMap;
import com.google.common.io.BaseEncoding;

import org.shredzone.acme4j.Authorization;
import org.shredzone.acme4j.Certificate;
import org.shredzone.acme4j.Registration;
import org.shredzone.acme4j.RegistrationBuilder;
import org.shredzone.acme4j.Session;
import org.shredzone.acme4j.Status;
import org.shredzone.acme4j.challenge.Challenge;
import org.shredzone.acme4j.challenge.Dns01Challenge;
import org.shredzone.acme4j.exception.AcmeConflictException;
import org.shredzone.acme4j.exception.AcmeException;
import org.shredzone.acme4j.util.CSRBuilder;
import org.shredzone.acme4j.util.CertificateUtils;
import org.shredzone.acme4j.util.KeyPairUtils;

import java.io.IOException;
import java.io.StringWriter;
import java.security.KeyPair;
import java.util.Map;

import in.tazj.k8s.letsencrypt.kubernetes.KeyPairManager;
import in.tazj.k8s.letsencrypt.util.DnsRecordObserver;
import in.tazj.k8s.letsencrypt.util.LetsencryptException;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

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

  public Map<String, String> requestCertificate(String domain) {
    final Registration registration = getRegistration();

    try {
      final Authorization authorization = registration.authorizeDomain(domain);
      final Challenge challenge = prepareDnsChallenge(authorization);
      completeChallenge(challenge);
      return generateSignCertificate(domain, registration);
    } catch (AcmeException | InterruptedException | IOException e) {
      throw new LetsencryptException(e.getMessage());
    }
  }

  private Map<String, String> generateSignCertificate(String domain, Registration registration)
      throws IOException, AcmeException {
    final KeyPair domainKeyPair = KeyPairUtils.createKeyPair(2048);
    final CSRBuilder csrBuilder = new CSRBuilder();
    csrBuilder.addDomain(domain);
    csrBuilder.sign(domainKeyPair);

    final Certificate certificate = registration.requestCertificate(csrBuilder.getEncoded());
    log.info("Successfully retrieved certificate for domain {}", domain);

    final StringWriter certWriter = new StringWriter();
    CertificateUtils.writeX509Certificate(certificate.download(), certWriter);

    final StringWriter chainWriter = new StringWriter();
    CertificateUtils.writeX509CertificateChain(certificate.downloadChain(), chainWriter);

    final StringWriter keyWriter = new StringWriter();
    KeyPairUtils.writeKeyPair(domainKeyPair, keyWriter);

    return ImmutableMap.of(
        "certificate.pem", base64EncodeWriter(certWriter),
        "chain.pem", base64EncodeWriter(chainWriter),
        "key.pem", base64EncodeWriter(keyWriter));
  }

  @SneakyThrows // UnsupportedEncodingException can not be thrown for UTF-8
  private String base64EncodeWriter(StringWriter writer) {
    return base64.encode(writer.toString().getBytes("UTF-8"));
  }

  private void completeChallenge(Challenge challenge) throws AcmeException, InterruptedException {
    int attempts = 10;
    challenge.trigger();

    while (challenge.getStatus() != Status.VALID && attempts > 0) {
      Thread.sleep(3000);
      challenge.update();
      attempts--;
    }

    if (challenge.getStatus() != Status.VALID) {
      log.error("Challenge failed, giving up.");
      throw new LetsencryptException("Challenge failed!");
    }
  }

  /** Creates a DNS challenge and calls the DNS responder with the challenge data.
   * Once this function returns the challenge, it should validate fine. */
  private Challenge prepareDnsChallenge(Authorization authorization) {
    final Dns01Challenge dns01Challenge = authorization.findChallenge(Dns01Challenge.TYPE);

    if (dns01Challenge == null) {
      log.error("Received no challenge from authorization for {}", authorization.getDomain());
      throw new LetsencryptException("Received no challenge");
    }

    final String challengeDomain = "_acme-challenge." + authorization.getDomain();
    dnsResponder.addChallengeRecord(challengeDomain, dns01Challenge.getDigest());

    // This will wait until the challenge record has become visible. Automatically times out after
    // 15 minutes.
    final DnsRecordObserver observer =
        new DnsRecordObserver(challengeDomain, authorization.getDomain(), dns01Challenge.getDigest());
    observer.observeDns();

    return dns01Challenge;
  }

  private Registration getRegistration() {
    final Session session = new Session(acmeServer, keyPairManager.getKeyPair());
    Registration registration;

    try {
      registration = new RegistrationBuilder().create(session);
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
}
