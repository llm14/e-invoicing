package com.example.einvoicing.crypto;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/** Generates throwaway CAs and signer certificates for tests. */
final class TestPki {

    record Issued(KeyPair keys, X509Certificate certificate) {
        SigningIdentity identity(Issued... above) {
            var chain = new java.util.ArrayList<X509Certificate>(List.of(certificate));
            for (var issuer : above) {
                chain.add(issuer.certificate());
            }
            return new SigningIdentity(keys.getPrivate(), chain);
        }
    }

    private TestPki() {}

    static Issued ca(String name, Instant notBefore, Instant notAfter) throws Exception {
        var keys = newKeys();
        var subject = new X500Name("CN=" + name);
        var builder = new JcaX509v3CertificateBuilder(
                subject, serial(), Date.from(notBefore), Date.from(notAfter), subject, keys.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        builder.addExtension(
                Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
        return new Issued(keys, sign(builder, keys));
    }

    static Issued signer(String name, Issued issuer, Instant notBefore, Instant notAfter)
            throws Exception {
        return signer(name, issuer, notBefore, notAfter, KeyUsage.digitalSignature);
    }

    static Issued signer(String name, Issued issuer, Instant notBefore, Instant notAfter, int usage)
            throws Exception {
        var keys = newKeys();
        var builder = new JcaX509v3CertificateBuilder(
                new X500Name(issuer.certificate().getSubjectX500Principal().getName()),
                serial(),
                Date.from(notBefore),
                Date.from(notAfter),
                new X500Name("CN=" + name),
                keys.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(usage));
        return new Issued(keys, sign(builder, issuer.keys()));
    }

    private static X509Certificate sign(JcaX509v3CertificateBuilder builder, KeyPair signingKeys)
            throws Exception {
        var signer = new JcaContentSignerBuilder("SHA256withRSA").build(signingKeys.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }

    private static KeyPair newKeys() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static BigInteger serial() {
        return new BigInteger(64, new java.security.SecureRandom());
    }
}
