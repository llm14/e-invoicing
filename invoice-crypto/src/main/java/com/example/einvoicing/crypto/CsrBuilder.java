package com.example.einvoicing.crypto;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.DefaultSignatureAlgorithmIdentifierFinder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.PKCS10CertificationRequestBuilder;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;

/**
 * Builds the certificate signing request (CSR) that is sent to a certificate authority.
 *
 * <p>A CSR says "here is my public key and my identity" and is signed with the matching private
 * key to prove the requester controls it. With a remote key that signature comes from the
 * {@link RemoteSigner}, so the private key is never needed locally.
 */
public final class CsrBuilder {

    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";

    private CsrBuilder() {}

    /**
     * @param subject distinguished name, e.g. {@code CN=ACME Lda,O=ACME,C=PT}
     * @param publicKey the public half of the key held by {@code signer}
     * @return the request in PEM ({@code -----BEGIN CERTIFICATE REQUEST-----}) form
     */
    public static String createPem(String subject, PublicKey publicKey, RemoteSigner signer) {
        PKCS10CertificationRequestBuilder builder =
                new JcaPKCS10CertificationRequestBuilder(new X500Name(subject), publicKey);
        PKCS10CertificationRequest request = builder.build(new RemoteContentSigner(signer));
        try (var out = new StringWriter(); var pem = new JcaPEMWriter(out)) {
            pem.writeObject(request);
            pem.flush();
            return out.toString();
        } catch (IOException e) {
            throw new IllegalStateException("Could not encode certificate request", e);
        }
    }

    /**
     * Parses a DER {@code SubjectPublicKeyInfo}, the format AWS KMS {@code GetPublicKey} returns.
     */
    public static PublicKey publicKeyFromDer(byte[] der) {
        try {
            var algorithm = SubjectPublicKeyInfo.getInstance(der).getAlgorithm().getAlgorithm();
            if (!org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers.rsaEncryption.equals(algorithm)) {
                throw new IllegalArgumentException("Only RSA public keys are supported");
            }
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Not a valid public key", e);
        }
    }

    /** Lets BouncyCastle ask the remote signer for the one signature a CSR needs. */
    private static final class RemoteContentSigner implements ContentSigner {

        private final RemoteSigner signer;
        private final ByteArrayOutputStream message = new ByteArrayOutputStream();

        RemoteContentSigner(RemoteSigner signer) {
            this.signer = signer;
        }

        @Override
        public AlgorithmIdentifier getAlgorithmIdentifier() {
            return new DefaultSignatureAlgorithmIdentifierFinder().find(SIGNATURE_ALGORITHM);
        }

        @Override
        public OutputStream getOutputStream() {
            return message;
        }

        @Override
        public byte[] getSignature() {
            return signer.sign(message.toByteArray());
        }
    }
}
