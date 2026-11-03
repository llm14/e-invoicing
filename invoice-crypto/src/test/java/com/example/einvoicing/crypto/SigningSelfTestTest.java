package com.example.einvoicing.crypto;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.einvoicing.crypto.TestPki.Issued;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SigningSelfTestTest {

    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");
    private static final Instant PAST = NOW.minus(Duration.ofDays(30));
    private static final Instant FUTURE = NOW.plus(Duration.ofDays(30));

    private static Issued ca;
    private static Issued otherCa;
    private static Issued signer;

    @BeforeAll
    static void generatePki() throws Exception {
        ca = TestPki.ca("Test CA", PAST, FUTURE);
        otherCa = TestPki.ca("Other CA", PAST, FUTURE);
        signer = TestPki.signer("ACME Signer", ca, PAST, FUTURE);
    }

    private static XmlSignatureValidator validatorTrusting(Issued anchor, Instant at) {
        return EnvelopedXmlSignatureValidator.forTrustedCertificates(
                Set.of(anchor.certificate()), Clock.fixed(at, ZoneOffset.UTC));
    }

    private static XmlSigner signerWith(RemoteSigner remote) {
        return new EnvelopedXmlSigner(SigningIdentity.remote(remote, List.of(signer.certificate())));
    }

    @Test
    void passesWhenKeyMatchesCertificateAndCaIsTrusted() {
        assertThatCode(() -> SigningSelfTest.check(
                        signerWith(new FakeKms(signer.keys())), validatorTrusting(ca, NOW)))
                .doesNotThrowAnyException();
    }

    @Test
    void failsWhenTheKeyDoesNotMatchTheCertificate() throws Exception {
        var wrongKeys = TestPki.signer("Someone Else", ca, PAST, FUTURE).keys();

        assertThatThrownBy(() -> SigningSelfTest.check(
                        signerWith(new FakeKms(wrongKeys)), validatorTrusting(ca, NOW)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not validate");
    }

    @Test
    void failsWhenTheIssuingCaIsNotTrusted() {
        assertThatThrownBy(() -> SigningSelfTest.check(
                        signerWith(new FakeKms(signer.keys())), validatorTrusting(otherCa, NOW)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not validate");
    }

    @Test
    void failsWhenTheCertificateHasExpired() {
        var afterExpiry = FUTURE.plus(Duration.ofDays(1));

        assertThatThrownBy(() -> SigningSelfTest.check(
                        signerWith(new FakeKms(signer.keys())), validatorTrusting(ca, afterExpiry)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not validate");
    }

    @Test
    void failsClearlyWhenTheRemoteSignerRefusesToSign() {
        RemoteSigner accessDenied = message -> {
            throw new IllegalStateException("AccessDeniedException: not authorized to perform kms:Sign");
        };

        assertThatThrownBy(() -> SigningSelfTest.check(signerWith(accessDenied), validatorTrusting(ca, NOW)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("could not sign")
                .hasRootCauseMessage("AccessDeniedException: not authorized to perform kms:Sign");
    }
}
