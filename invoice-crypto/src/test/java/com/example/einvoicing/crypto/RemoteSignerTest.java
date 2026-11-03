package com.example.einvoicing.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.einvoicing.crypto.TestPki.Issued;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Signs through a {@link RemoteSigner}: the code under test never reads the private key. */
class RemoteSignerTest {

    private static final String INVOICE =
            "<Invoice><Issuer>ACME</Issuer><Total>100.00</Total></Invoice>";

    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static Issued ca;
    private static Issued signer;

    @BeforeAll
    static void generatePki() throws Exception {
        ca = TestPki.ca("Test CA", NOW.minus(Duration.ofDays(30)), NOW.plus(Duration.ofDays(30)));
        signer = TestPki.signer("ACME Signer", ca, NOW.minus(Duration.ofDays(30)), NOW.plus(Duration.ofDays(30)));
    }

    private static EnvelopedXmlSignatureValidator validatorTrustingCa() {
        return EnvelopedXmlSignatureValidator.forTrustedCertificates(Set.of(ca.certificate()), CLOCK);
    }

    private static SigningIdentity identityBackedBy(FakeKms kms) {
        return SigningIdentity.remote(kms, List.<X509Certificate>of(signer.certificate()));
    }

    @Test
    void signatureProducedByTheRemoteSignerValidates() {
        var kms = new FakeKms(signer.keys());

        var signed = new EnvelopedXmlSigner(identityBackedBy(kms)).sign(INVOICE);

        assertThat(validatorTrustingCa().validate(signed)).isTrue();
    }

    @Test
    void tamperingAfterRemoteSigningIsDetected() {
        var signed = new EnvelopedXmlSigner(identityBackedBy(new FakeKms(signer.keys()))).sign(INVOICE);

        assertThat(validatorTrustingCa().validate(signed.replace("100.00", "1.00"))).isFalse();
    }

    @Test
    void remoteSignerReceivesOnlyTheSignedInfoNotTheWholeInvoice() {
        var kms = new FakeKms(signer.keys());

        new EnvelopedXmlSigner(identityBackedBy(kms)).sign(INVOICE);

        assertThat(kms.received).hasSize(1);
        var message = new String(kms.received.get(0), StandardCharsets.UTF_8);
        assertThat(message).contains("<SignedInfo").contains("<DigestValue>");
        assertThat(message).doesNotContain("ACME").doesNotContain("100.00");
    }

    @Test
    void remoteKeyExposesNoKeyMaterial() {
        var key = new RemotePrivateKey(new FakeKms(signer.keys()));

        assertThat(key.getEncoded()).isNull();
        assertThat(key.getFormat()).isNull();
    }

    @Test
    void signatureFromAKeyThatDoesNotMatchTheCertificateIsRejected() throws Exception {
        var otherKeys = TestPki.signer("Someone Else", ca, NOW.minus(Duration.ofDays(30)), NOW.plus(Duration.ofDays(30))).keys();

        var signed = new EnvelopedXmlSigner(identityBackedBy(new FakeKms(otherKeys))).sign(INVOICE);

        assertThat(validatorTrustingCa().validate(signed)).isFalse();
    }
}
