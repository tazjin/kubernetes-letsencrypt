package `in`.tazj.k8s.letsencrypt.acme

import `in`.tazj.k8s.letsencrypt.kubernetes.KeyPairManager
import `in`.tazj.k8s.letsencrypt.model.CertificateResponse
import `in`.tazj.k8s.letsencrypt.util.DnsRecordObserver
import `in`.tazj.k8s.letsencrypt.util.LetsencryptException
import `in`.tazj.k8s.letsencrypt.util.SecretFilenames
import com.google.common.collect.ImmutableMap
import com.google.common.io.BaseEncoding
import org.shredzone.acme4j.*
import org.shredzone.acme4j.challenge.Challenge
import org.shredzone.acme4j.challenge.Dns01Challenge
import org.shredzone.acme4j.exception.AcmeConflictException
import org.shredzone.acme4j.exception.AcmeException
import org.shredzone.acme4j.exception.AcmeUnauthorizedException
import org.shredzone.acme4j.util.CSRBuilder
import org.shredzone.acme4j.util.CertificateUtils
import org.shredzone.acme4j.util.KeyPairUtils
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.StringWriter


/**
 * Requests certificates from a specified ACME server.
 */
class CertificateRequestHandler(
        val acmeServer: String,
        val secretFilenames: SecretFilenames,
        val keyPairManager: KeyPairManager,
        val dnsResponder: DnsResponder
) {
    private val log = LoggerFactory.getLogger(this::class.java)
    private val base64 = BaseEncoding.base64()

    fun requestCertificate(domains: List<String>): CertificateResponse {
        val registration = getRegistration()

        try {
            // Complete domain challenges in parallel
            domains.parallelStream().forEach { domain -> authorizeDomain(registration, domain) }
            return generateSignCertificate(domains, registration)
        } catch (e: AcmeUnauthorizedException) {
            val agreementError = "Must agree to subscriber agreement before any further actions"
            if (e.message?.contains(agreementError) ?: false) {
                agreeToSubscriberLicense(registration)
                return requestCertificate(domains)
            } else {
                throw LetsencryptException(e.message)
            }

        } catch (e: AcmeException) {
            e.printStackTrace()
            throw LetsencryptException(e.message)
        } catch (e: IOException) {
            e.printStackTrace()
            throw LetsencryptException(e.message)
        }

    }

    /**
     * Performs the authorization flow for a single domain after which the domain is authorized for
     * the given registration.
     */
    private fun authorizeDomain(registration: Registration, domain: String) {
        try {
            val authorization = getAuthorization(registration, domain)

            if (authorization.status == Status.VALID) {
                log.info("Authorization for {} still valid, not issuing new challenge", domain)
                return
            }

            log.info("Issuing new challenge for {}", domain)

            val challengeTuple = prepareDnsChallenge(authorization)
            completeChallenge(challengeTuple.first)
            challengeTuple.second.run()
        } catch (e: AcmeException) {
            e.printStackTrace()
            throw LetsencryptException(e.message)
        }

    }

    /** Let's Encrypt uses synchronous nonces to prevent request spoofing.  */
    @Synchronized
    private fun getAuthorization(registration: Registration, domain: String): Authorization {
        val authorization = registration.authorizeDomain(domain)
        return authorization
    }

    @Throws(IOException::class, AcmeException::class)
    private fun generateSignCertificate(domains: List<String>,
                                        registration: Registration): CertificateResponse {
        val domainKeyPair = KeyPairUtils.createKeyPair(2048)
        val csrBuilder = CSRBuilder()
        domains.forEach { csrBuilder.addDomain(it) }
        csrBuilder.sign(domainKeyPair)

        val certificate = registration.requestCertificate(csrBuilder.encoded)
        val downloadedCertificate = certificate.download()
        log.info("Successfully retrieved certificate for domains: {}", domains.toString())

        val certWriter = StringWriter()
        CertificateUtils.writeX509Certificate(downloadedCertificate, certWriter)

        val downloadedChain = certificate.downloadChain()
        val fullchainWriter = StringWriter()
        CertificateUtils.writeX509CertificateChain(fullchainWriter, downloadedCertificate, *downloadedChain)

        val keyWriter = StringWriter()
        KeyPairUtils.writeKeyPair(domainKeyPair, keyWriter)

        val certificateFiles = ImmutableMap.of(
                secretFilenames.certificate, base64EncodeWriter(certWriter),
                secretFilenames.chain, base64EncodeWriter(fullchainWriter),
                secretFilenames.key, base64EncodeWriter(keyWriter),
                secretFilenames.fullchain, base64EncodeWriter(fullchainWriter))

        return CertificateResponse(domains, certificateFiles,
                downloadedCertificate.notAfter, acmeServer)
    }


    private fun base64EncodeWriter(vararg writer: StringWriter): String {
        var current = ""

        for (stringWriter in writer) {
            current += stringWriter.toString()
        }

        return base64.encode(current.toByteArray(charset("UTF-8")))
    }

    /**
     * Attempt to validate the LetsEncrypt challenge with the retry policy specified above.
     * If the challenge does not complete within 10 minutes it is assumed to have failed and an
     * exception will be thrown.
     */
    @Synchronized private fun completeChallenge(challenge: Challenge) {
        challenge.trigger()
        challenge.update()

        while (challenge.status == Status.PENDING) {
            challenge.update()
            Thread.sleep(100)
        }

        if (challenge.status == Status.INVALID) {
            log.error("Challenge {} failed", challenge.location)
            throw LetsencryptException("Failed due to invalid challenge")
        }

        log.info("Challenge {} completed", challenge.location)
    }


    /**
     * Creates a DNS challenge and calls the DNS responder with the challenge data.
     * Once this function returns the challenge, it should validate fine.

     * The returned tuple contains the challenge and a cleanup closure which can be called after
     * challenge validation to remove the DNS record used for verification.
     */
    private fun prepareDnsChallenge(authorization: Authorization): Pair<Challenge, Runnable> {
        val dns01Challenge = authorization.findChallenge<Dns01Challenge>(Dns01Challenge.TYPE)

        if (dns01Challenge == null) {
            log.error("Received no challenge from authorization for {}", authorization.domain)
            throw LetsencryptException("Received no challenge")
        }

        val challengeRecord = "_acme-challenge." + authorization.domain
        val rootZone = dnsResponder.addChallengeRecord(challengeRecord, dns01Challenge.digest)

        val observer = DnsRecordObserver(challengeRecord, rootZone, dns01Challenge.digest)
        observer.observeDns()

        val cleanup: Runnable = Runnable {
            dnsResponder.removeChallengeRecord(challengeRecord, dns01Challenge.digest)
        }

        return Pair(dns01Challenge, cleanup)
    }

    private fun getRegistration(): Registration {
        val session = Session(acmeServer, keyPairManager.keyPair)
        var registration: Registration

        try {
            registration = RegistrationBuilder().create(session)
            agreeToSubscriberLicense(registration)
            log.info("Created new ACME user, URI: {}", registration.location)
        } catch (e: AcmeConflictException) {
            registration = Registration.bind(session, e.location)
            log.info("Using existing ACME user: {}", registration.location)
        } catch (e: AcmeException) {
            log.error("Unexpected error while setting up registration: {}", e)
            throw LetsencryptException(e.message)
        }

        return registration
    }

    private fun agreeToSubscriberLicense(registration: Registration) {
        val agreementUri = registration.agreement
        log.info("Agreeing to Let's Encrypt subscriber agreement. "
                + "Terms are available at {}", agreementUri)

        try {
            registration.modify().setAgreement(agreementUri).commit()
        } catch (e: AcmeException) {
            log.error("Could not agree to new subscriber agreement: {}", e.message)
            throw LetsencryptException("Could not agree to subscriber agreement.")
        }

    }
}