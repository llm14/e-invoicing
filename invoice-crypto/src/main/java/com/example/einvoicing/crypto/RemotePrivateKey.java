package com.example.einvoicing.crypto;

import java.io.NotSerializableException;
import java.io.ObjectOutputStream;
import java.security.PrivateKey;
import java.util.Objects;

/**
 * A {@link PrivateKey} in type only: it carries a {@link RemoteSigner}, not key material. The JDK
 * signing code can be handed this like any private key, and the signature operation is forwarded
 * to the remote signer by {@link RemoteSignatureProvider}.
 */
public final class RemotePrivateKey implements PrivateKey {

    private static final long serialVersionUID = 1L;

    private final transient RemoteSigner signer;

    public RemotePrivateKey(RemoteSigner signer) {
        this.signer = Objects.requireNonNull(signer);
        RemoteSignatureProvider.ensureInstalled();
    }

    RemoteSigner signer() {
        return signer;
    }

    @Override
    public String getAlgorithm() {
        return "RSA";
    }

    /** There is no encoding: the key cannot be exported. */
    @Override
    public String getFormat() {
        return null;
    }

    @Override
    public byte[] getEncoded() {
        return null;
    }

    private void writeObject(ObjectOutputStream out) throws NotSerializableException {
        throw new NotSerializableException(getClass().getName());
    }
}
