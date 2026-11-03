package com.example.einvoicing.crypto;

/**
 * Proves, before any real invoice is signed, that the signing setup works end to end: the signer
 * can produce a signature (key reachable, permissions in place) and that signature validates
 * against the trusted certificates (key matches certificate, chain complete, not expired, issuing
 * CA trusted).
 *
 * <p>This is a configuration smoke test, not a security control: it signs and verifies inside the
 * same process, so it cannot detect an attacker, only our own mistakes.
 */
public final class SigningSelfTest {

    private static final String PROBE = "<SigningSelfTest>probe</SigningSelfTest>";

    private SigningSelfTest() {}

    /** @throws IllegalStateException with the likely causes if signing or validation fails */
    public static void check(XmlSigner signer, XmlSignatureValidator validator) {
        String signed;
        try {
            signed = signer.sign(PROBE);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "Signing self-test failed: could not sign a probe document. "
                            + "Check that the signing key exists and that this application is allowed to use it.",
                    e);
        }
        if (!validator.validate(signed)) {
            throw new IllegalStateException(
                    "Signing self-test failed: the signature on a probe document does not validate against "
                            + "the trusted certificates. Likely causes: the signing key does not match its "
                            + "certificate, the certificate or its chain is expired or incomplete, or the "
                            + "trust store does not contain the issuing CA.");
        }
    }
}
