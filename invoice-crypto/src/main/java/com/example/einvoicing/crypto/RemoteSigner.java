package com.example.einvoicing.crypto;

/**
 * Something that holds a private key we cannot read (a cloud KMS, an HSM) and signs on our behalf.
 * The key never crosses this interface: callers hand over bytes and get a signature back.
 */
public interface RemoteSigner {

    /**
     * Signs with RSASSA-PKCS1-v1_5 over SHA-256. The implementation receives the raw message and
     * is responsible for hashing it (AWS KMS: {@code MessageType=RAW},
     * {@code SigningAlgorithm=RSASSA_PKCS1_V1_5_SHA_256}, which accepts messages up to 4096 bytes;
     * an XMLDSig {@code SignedInfo} is far smaller).
     */
    byte[] sign(byte[] message);
}
