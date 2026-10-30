package com.example.einvoicing.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.einvoicing.crypto.TestPki.Issued;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CertificateSignatureTest {

    private static final String INVOICE =
            "<Invoice><Issuer>ACME</Issuer><Total>100.00</Total></Invoice>";
    private static final char[] PASSWORD = "changeit".toCharArray();

    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");
    private static final Instant PAST = NOW.minus(Duration.ofDays(30));
    private static final Instant FUTURE = NOW.plus(Duration.ofDays(30));
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static Issued ca;
    private static Issued otherCa;
    private static Issued signer;

    @BeforeAll
    static void generatePki() throws Exception {
        ca = TestPki.ca("Test CA", PAST, FUTURE);
        otherCa = TestPki.ca("Other CA", PAST, FUTURE);
        signer = TestPki.signer("ACME Signer", ca, PAST, FUTURE);
    }

    private static EnvelopedXmlSignatureValidator validatorTrusting(Issued... anchors) {
        return EnvelopedXmlSignatureValidator.forTrustedCertificates(
                Set.of(java.util.Arrays.stream(anchors).map(Issued::certificate).toArray(java.security.cert.X509Certificate[]::new)),
                CLOCK);
    }

    private static String sign(SigningIdentity identity) {
        return new EnvelopedXmlSigner(identity).sign(INVOICE);
    }

    @Test
    void signatureFromTrustedChainValidates() {
        assertThat(validatorTrusting(ca).validate(sign(signer.identity()))).isTrue();
    }

    @Test
    void chainIncludingTheRootValidates() {
        assertThat(validatorTrusting(ca).validate(sign(signer.identity(ca)))).isTrue();
    }

    @Test
    void signerCertificateTrustedDirectlyValidates() {
        assertThat(validatorTrusting(signer).validate(sign(signer.identity()))).isTrue();
    }

    @Test
    void untrustedIssuerFailsValidation() {
        assertThat(validatorTrusting(otherCa).validate(sign(signer.identity()))).isFalse();
    }

    @Test
    void expiredSignerCertificateFailsValidation() throws Exception {
        var expired = TestPki.signer("Old Signer", ca, PAST, NOW.minus(Duration.ofDays(1)));
        assertThat(validatorTrusting(ca).validate(sign(expired.identity()))).isFalse();
    }

    @Test
    void notYetValidSignerCertificateFailsValidation() throws Exception {
        var early = TestPki.signer("Future Signer", ca, NOW.plus(Duration.ofDays(1)), FUTURE);
        assertThat(validatorTrusting(ca).validate(sign(early.identity()))).isFalse();
    }

    @Test
    void tamperedContentFailsValidation() {
        var xml = sign(signer.identity()).replace("100.00", "999.00");
        assertThat(validatorTrusting(ca).validate(xml)).isFalse();
    }

    @Test
    void signatureWithoutCertificateFailsValidation() {
        var xml = new EnvelopedXmlSigner(signer.keys().getPrivate()).sign(INVOICE);
        assertThat(validatorTrusting(ca).validate(xml)).isFalse();
    }

    @Test
    void certificateKeyDifferentFromSigningKeyFailsValidation() throws Exception {
        var impostor = new SigningIdentity(otherCa.keys().getPrivate(), List.of(signer.certificate()));
        assertThat(validatorTrusting(ca).validate(sign(impostor))).isFalse();
    }

    @Test
    void certificateNotAllowedToSignIsRejected() throws Exception {
        var encryptionOnly =
                TestPki.signer("Encrypt Only", ca, PAST, FUTURE, KeyUsage.keyEncipherment);
        var validator = new CertificateValidator(Set.of(ca.certificate()), CLOCK);
        assertThatThrownBy(() -> validator.validate(List.of(encryptionOnly.certificate())))
                .isInstanceOf(CertificateValidationException.class);
    }

    @Test
    void certificateValidatorReportsExpiry() throws Exception {
        var expired = TestPki.signer("Old Signer", ca, PAST, NOW.minus(Duration.ofDays(1)));
        var validator = new CertificateValidator(Set.of(ca.certificate()), CLOCK);
        assertThatThrownBy(() -> validator.validate(List.of(expired.certificate())))
                .isInstanceOf(CertificateValidationException.class)
                .hasMessageContaining("not valid");
    }

    @Test
    void keyStoreRoundTrip(@TempDir Path dir) throws Exception {
        var identityFile = dir.resolve("signer.p12");
        var trustFile = dir.resolve("trust.p12");

        var identityStore = KeyStore.getInstance("PKCS12");
        identityStore.load(null, null);
        identityStore.setKeyEntry(
                "signer",
                signer.keys().getPrivate(),
                PASSWORD,
                new java.security.cert.Certificate[] {signer.certificate(), ca.certificate()});
        try (var out = java.nio.file.Files.newOutputStream(identityFile)) {
            identityStore.store(out, PASSWORD);
        }
        var trustStore = KeyStore.getInstance("PKCS12");
        trustStore.load(null, null);
        trustStore.setCertificateEntry("ca", ca.certificate());
        try (var out = java.nio.file.Files.newOutputStream(trustFile)) {
            trustStore.store(out, PASSWORD);
        }

        var identity = KeyStoreLoader.loadSigningIdentity(identityFile, PASSWORD, "signer");
        var anchors = KeyStoreLoader.loadTrustAnchors(trustFile, PASSWORD);

        assertThat(identity.chain()).hasSize(2);
        assertThat(identity.certificate()).isEqualTo(signer.certificate());
        assertThat(anchors).containsExactly(ca.certificate());
        assertThat(EnvelopedXmlSignatureValidator.forTrustedCertificates(anchors, CLOCK)
                        .validate(sign(identity)))
                .isTrue();

        assertThatThrownBy(() -> KeyStoreLoader.loadSigningIdentity(identityFile, "wrong".toCharArray(), "signer"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> KeyStoreLoader.loadSigningIdentity(identityFile, PASSWORD, "missing"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
