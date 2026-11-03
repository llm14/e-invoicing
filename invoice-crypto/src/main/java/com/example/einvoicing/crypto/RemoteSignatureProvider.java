package com.example.einvoicing.crypto;

import java.io.ByteArrayOutputStream;
import java.security.InvalidKeyException;
import java.security.InvalidParameterException;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.PublicKey;
import java.security.Security;
import java.security.SignatureException;
import java.security.SignatureSpi;
import java.util.List;
import java.util.Map;

/**
 * Registers {@code SHA256withRSA} for {@link RemotePrivateKey}s only. The JDK picks a provider by
 * trying each in turn until one accepts the key, so ordinary keys keep using the built-in
 * implementation and a remote key lands here.
 */
final class RemoteSignatureProvider extends Provider {

    static final String NAME = "RemoteSigner";

    private RemoteSignatureProvider() {
        super(NAME, "1.0", "Signatures computed by a RemoteSigner; the private key is never held locally");
        putService(new Service(
                this,
                "Signature",
                "SHA256withRSA",
                RemoteSignature.class.getName(),
                List.of(),
                Map.of("SupportedKeyClasses", RemotePrivateKey.class.getName())) {
            @Override
            public Object newInstance(Object constructorParameter) {
                return new RemoteSignature();
            }
        });
    }

    static synchronized void ensureInstalled() {
        if (Security.getProvider(NAME) == null) {
            Security.addProvider(new RemoteSignatureProvider());
        }
    }

    /** Buffers everything fed to the signature, then asks the remote signer for the result. */
    static final class RemoteSignature extends SignatureSpi {

        private final ByteArrayOutputStream message = new ByteArrayOutputStream();
        private RemoteSigner signer;

        @Override
        protected void engineInitSign(PrivateKey key) throws InvalidKeyException {
            if (!(key instanceof RemotePrivateKey remote)) {
                throw new InvalidKeyException("Only RemotePrivateKey is supported");
            }
            signer = remote.signer();
            message.reset();
        }

        @Override
        protected void engineInitVerify(PublicKey key) throws InvalidKeyException {
            throw new InvalidKeyException("Remote signatures are sign-only; verify with the public key");
        }

        @Override
        protected void engineUpdate(byte b) {
            message.write(b);
        }

        @Override
        protected void engineUpdate(byte[] bytes, int offset, int length) {
            message.write(bytes, offset, length);
        }

        @Override
        protected byte[] engineSign() throws SignatureException {
            try {
                return signer.sign(message.toByteArray());
            } catch (RuntimeException e) {
                throw new SignatureException("Remote signer failed", e);
            } finally {
                message.reset();
            }
        }

        @Override
        protected boolean engineVerify(byte[] signature) throws SignatureException {
            throw new SignatureException("Remote signatures are sign-only");
        }

        @Override
        @Deprecated
        protected void engineSetParameter(String param, Object value) {
            throw new InvalidParameterException("No parameters supported");
        }

        @Override
        @Deprecated
        protected Object engineGetParameter(String param) {
            throw new InvalidParameterException("No parameters supported");
        }
    }
}
