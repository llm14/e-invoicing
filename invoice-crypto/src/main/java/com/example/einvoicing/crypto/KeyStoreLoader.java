package com.example.einvoicing.crypto;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Loads signing material and trust anchors from PKCS#12 stores. */
public final class KeyStoreLoader {

    private KeyStoreLoader() {}

    public static SigningIdentity loadSigningIdentity(Path file, char[] password, String alias) {
        var keyStore = open(file, password);
        try {
            if (!(keyStore.getKey(alias, password) instanceof PrivateKey privateKey)) {
                throw new IllegalArgumentException("No private key entry for alias '" + alias + "'");
            }
            var chain = new ArrayList<X509Certificate>();
            for (Certificate certificate : keyStore.getCertificateChain(alias)) {
                chain.add((X509Certificate) certificate);
            }
            return new SigningIdentity(privateKey, chain);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not read key entry '" + alias + "'", e);
        }
    }

    /** Returns every trusted-certificate entry of the store. */
    public static Set<X509Certificate> loadTrustAnchors(Path file, char[] password) {
        var keyStore = open(file, password);
        var anchors = new LinkedHashSet<X509Certificate>();
        try {
            for (String alias : Collections.list(keyStore.aliases())) {
                if (keyStore.isCertificateEntry(alias)) {
                    anchors.add((X509Certificate) keyStore.getCertificate(alias));
                }
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not read trust store", e);
        }
        if (anchors.isEmpty()) {
            throw new IllegalArgumentException("Trust store contains no trusted certificates");
        }
        return anchors;
    }

    private static KeyStore open(Path file, char[] password) {
        try (InputStream in = Files.newInputStream(file)) {
            var keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(in, password);
            return keyStore;
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("Could not open key store " + file, e);
        }
    }
}
