package com.example.einvoicing.crypto;

import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.util.List;
import javax.xml.crypto.AlgorithmMethod;
import javax.xml.crypto.KeySelector;
import javax.xml.crypto.KeySelectorException;
import javax.xml.crypto.KeySelectorResult;
import javax.xml.crypto.XMLCryptoContext;
import javax.xml.crypto.dsig.keyinfo.KeyInfo;
import javax.xml.crypto.dsig.keyinfo.X509Data;

/** Resolves the verification key from the document's X509Data, only after the chain is trusted. */
final class X509ChainKeySelector extends KeySelector {

    private final CertificateValidator validator;

    X509ChainKeySelector(CertificateValidator validator) {
        this.validator = validator;
    }

    @Override
    public KeySelectorResult select(
            KeyInfo keyInfo, Purpose purpose, AlgorithmMethod method, XMLCryptoContext context)
            throws KeySelectorException {
        if (keyInfo == null) {
            throw new KeySelectorException("Signature has no KeyInfo");
        }
        for (Object info : keyInfo.getContent()) {
            if (info instanceof X509Data x509) {
                List<X509Certificate> chain = x509.getContent().stream()
                        .filter(X509Certificate.class::isInstance)
                        .map(X509Certificate.class::cast)
                        .toList();
                if (chain.isEmpty()) {
                    continue;
                }
                try {
                    validator.validate(chain);
                } catch (CertificateValidationException e) {
                    throw new KeySelectorException(e);
                }
                PublicKey key = chain.get(0).getPublicKey();
                return () -> key;
            }
        }
        throw new KeySelectorException("Signature KeyInfo has no X509 certificate");
    }
}
