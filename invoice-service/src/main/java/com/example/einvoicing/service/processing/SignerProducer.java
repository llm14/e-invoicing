package com.example.einvoicing.service.processing;

import com.example.einvoicing.crypto.EnvelopedXmlSigner;
import com.example.einvoicing.crypto.KeyStoreLoader;
import com.example.einvoicing.crypto.XmlSigner;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

public class SignerProducer {

    @ConfigProperty(name = "app.signing.keystore")
    Optional<String> keystore;

    @ConfigProperty(name = "app.signing.password")
    Optional<String> password;

    @ConfigProperty(name = "app.signing.alias", defaultValue = "signer")
    String alias;

    /**
     * Uses the configured PKCS#12 keystore. Without one, falls back to a throwaway key generated
     * at startup, so signatures cannot be verified by anyone outside this process (dev only).
     */
    @Produces
    @ApplicationScoped
    XmlSigner xmlSigner() throws NoSuchAlgorithmException {
        if (keystore.isPresent()) {
            var secret = password.orElseThrow(
                    () -> new IllegalStateException("app.signing.password is required with app.signing.keystore"));
            return new EnvelopedXmlSigner(
                    KeyStoreLoader.loadSigningIdentity(Path.of(keystore.get()), secret.toCharArray(), alias));
        }
        Log.warn("app.signing.keystore is not set: signing with an ephemeral key (development only)");
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return new EnvelopedXmlSigner(generator.generateKeyPair().getPrivate());
    }
}
