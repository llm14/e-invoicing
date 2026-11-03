package com.example.einvoicing.service.processing;

import com.example.einvoicing.crypto.EnvelopedXmlSignatureValidator;
import com.example.einvoicing.crypto.KeyStoreLoader;
import com.example.einvoicing.crypto.SigningSelfTest;
import com.example.einvoicing.crypto.XmlSigner;
import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Signs and validates a probe document when the application starts, so a signing misconfiguration
 * stops the deployment instead of surfacing as rejected invoices. Runs only when a trust store is
 * configured, because without trusted certificates there is nothing to validate against.
 */
@ApplicationScoped
public class SigningStartupCheck {

    @ConfigProperty(name = "app.signing.truststore")
    Optional<String> truststore;

    @ConfigProperty(name = "app.signing.truststore-password")
    Optional<String> truststorePassword;

    @Inject
    XmlSigner signer;

    void onStart(@Observes StartupEvent event) {
        if (truststore.isEmpty()) {
            Log.warn("app.signing.truststore is not set: skipping the signing self-test");
            return;
        }
        var secret = truststorePassword.orElseThrow(() -> new IllegalStateException(
                "app.signing.truststore-password is required with app.signing.truststore"));
        var anchors = KeyStoreLoader.loadTrustAnchors(Path.of(truststore.get()), secret.toCharArray());
        SigningSelfTest.check(signer, EnvelopedXmlSignatureValidator.forTrustedCertificates(anchors, Clock.systemUTC()));
        Log.info("Signing self-test passed");
    }
}
