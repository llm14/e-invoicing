package com.example.einvoicing.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class EnvelopedXmlSignatureTest {

    private static final String INVOICE =
            "<Invoice><Issuer>ACME</Issuer><Total>100.00</Total></Invoice>";

    private static KeyPair keys;
    private static KeyPair otherKeys;

    @BeforeAll
    static void generateKeys() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keys = generator.generateKeyPair();
        otherKeys = generator.generateKeyPair();
    }

    private String signed() {
        return new EnvelopedXmlSigner(keys.getPrivate()).sign(INVOICE);
    }

    private boolean validate(String xml, KeyPair trusted) {
        return new EnvelopedXmlSignatureValidator(trusted.getPublic()).validate(xml);
    }

    @Test
    void signedDocumentValidates() {
        var xml = signed();
        assertThat(xml).contains("Signature");
        assertThat(validate(xml, keys)).isTrue();
    }

    @Test
    void tamperedContentFailsValidation() {
        assertThat(validate(signed().replace("100.00", "999.00"), keys)).isFalse();
    }

    @Test
    void wrongTrustedKeyFailsValidation() {
        assertThat(validate(signed(), otherKeys)).isFalse();
    }

    @Test
    void unsignedDocumentFailsValidation() {
        assertThat(validate(INVOICE, keys)).isFalse();
    }

    @Test
    void documentWithTwoSignaturesIsRejected() {
        var twice = new EnvelopedXmlSigner(keys.getPrivate()).sign(signed());
        assertThat(validate(twice, keys)).isFalse();
    }

    @Test
    void doctypeIsRejected() {
        var xxe = "<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><Invoice>&e;</Invoice>";
        assertThatThrownBy(() -> validate(xxe, keys)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void malformedXmlIsRejected() {
        assertThatThrownBy(() -> validate("<Invoice>", keys)).isInstanceOf(IllegalArgumentException.class);
    }
}
