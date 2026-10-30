package com.example.einvoicing.crypto;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;

/** A private key together with its certificate chain, signer certificate first. */
public record SigningIdentity(PrivateKey privateKey, List<X509Certificate> chain) {

    public SigningIdentity {
        if (chain.isEmpty()) {
            throw new IllegalArgumentException("Certificate chain must not be empty");
        }
        chain = List.copyOf(chain);
    }

    public X509Certificate certificate() {
        return chain.get(0);
    }
}
