package com.example.einvoicing.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.einvoicing.crypto.TestPki.Issued;
import java.io.StringReader;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.junit.jupiter.api.Test;

/** The whole certificate lifecycle with a key we never hold: CSR, issuance, signing, validation. */
class CsrBuilderTest {

    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");
    private static final Instant PAST = NOW.minus(Duration.ofDays(30));
    private static final Instant FUTURE = NOW.plus(Duration.ofDays(30));
    private static final String SUBJECT = "CN=ACME Lda,O=ACME,C=PT";

    private static PKCS10CertificationRequest parse(String pem) throws Exception {
        try (var parser = new PEMParser(new StringReader(pem))) {
            return (PKCS10CertificationRequest) parser.readObject();
        }
    }

    @Test
    void csrIsPemAndCarriesSubjectAndPublicKey() throws Exception {
        var kms = new FakeKms(KeyPairGenerator.getInstance("RSA").generateKeyPair());
        var publicKey = kms.publicKey();

        var pem = CsrBuilder.createPem(SUBJECT, publicKey, kms);

        assertThat(pem).startsWith("-----BEGIN CERTIFICATE REQUEST-----");
        var request = parse(pem);
        assertThat(request.getSubject().toString()).isEqualTo("CN=ACME Lda,O=ACME,C=PT");
        assertThat(request.getSubjectPublicKeyInfo().getEncoded()).isEqualTo(publicKey.getEncoded());
    }

    @Test
    void csrSignatureProvesTheRequesterControlsTheKey() throws Exception {
        var kms = new FakeKms(KeyPairGenerator.getInstance("RSA").generateKeyPair());

        var request = parse(CsrBuilder.createPem(SUBJECT, kms.publicKey(), kms));

        var verifier = new JcaContentVerifierProviderBuilder().build(kms.publicKey());
        assertThat(request.isSignatureValid(verifier)).isTrue();
    }

    @Test
    void csrClaimingSomeoneElsesPublicKeyFailsProofOfPossession() throws Exception {
        var mine = new FakeKms(KeyPairGenerator.getInstance("RSA").generateKeyPair());
        var theirs = new FakeKms(KeyPairGenerator.getInstance("RSA").generateKeyPair());

        // asks for a certificate on THEIR public key but can only sign with MY key
        var request = parse(CsrBuilder.createPem(SUBJECT, theirs.publicKey(), mine));

        var verifier = new JcaContentVerifierProviderBuilder().build(theirs.publicKey());
        assertThat(request.isSignatureValid(verifier)).isFalse();
    }

    @Test
    void publicKeyFromDerRoundTripsAndRejectsGarbage() throws Exception {
        var keys = KeyPairGenerator.getInstance("RSA").generateKeyPair();

        assertThat(CsrBuilder.publicKeyFromDer(keys.getPublic().getEncoded())).isEqualTo(keys.getPublic());
        assertThatThrownBy(() -> CsrBuilder.publicKeyFromDer(new byte[] {1, 2, 3}))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void fullLifecycle_keyNeverLeavesTheKms() throws Exception {
        // 1. the KMS creates a key; we only ever learn its public key (as DER, like GetPublicKey)
        var kms = new FakeKms(KeyPairGenerator.getInstance("RSA").generateKeyPair());
        var publicKey = CsrBuilder.publicKeyFromDer(kms.publicKeyDer());

        // 2. we build a CSR; the KMS signs it
        var pem = CsrBuilder.createPem(SUBJECT, publicKey, kms);

        // 3. the CA checks the proof of possession and issues a certificate
        Issued ca = TestPki.ca("Dev Root CA", PAST, FUTURE);
        var request = parse(pem);
        assertThat(request.isSignatureValid(new JcaContentVerifierProviderBuilder().build(publicKey))).isTrue();
        var certificate = TestPki.issueFromRequest(ca, request, PAST, FUTURE);

        // 4. we sign an invoice with the certificate chain and the KMS-held key
        var identity = SigningIdentity.remote(kms, List.of(certificate, ca.certificate()));
        var signed = new EnvelopedXmlSigner(identity).sign("<Invoice><Total>100.00</Total></Invoice>");

        // 5. a recipient that trusts only the CA accepts it
        var validator = EnvelopedXmlSignatureValidator.forTrustedCertificates(
                Set.of(ca.certificate()), Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(validator.validate(signed)).isTrue();
    }
}
