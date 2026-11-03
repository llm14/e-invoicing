package com.example.einvoicing.crypto;

import java.security.KeyPair;
import java.security.Signature;
import java.util.ArrayList;
import java.util.List;

/**
 * Stands in for AWS KMS. Like KMS, it keeps the private key to itself: the only thing it exposes
 * is {@link #sign}, and it records every message it was asked to sign.
 */
final class FakeKms implements RemoteSigner {

    private final KeyPair keys;
    final List<byte[]> received = new ArrayList<>();

    FakeKms(KeyPair keys) {
        this.keys = keys;
    }

    /** Like KMS {@code GetPublicKey}: the public half only. */
    java.security.PublicKey publicKey() {
        return keys.getPublic();
    }

    /** DER SubjectPublicKeyInfo, exactly the format KMS {@code GetPublicKey} returns. */
    byte[] publicKeyDer() {
        return keys.getPublic().getEncoded();
    }

    @Override
    public byte[] sign(byte[] message) {
        received.add(message);
        try {
            var signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(keys.getPrivate());
            signature.update(message);
            return signature.sign();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
