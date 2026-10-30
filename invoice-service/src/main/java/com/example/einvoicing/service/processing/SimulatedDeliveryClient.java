package com.example.einvoicing.service.processing;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Stand-in for a real recipient or tax-authority endpoint. {@code app.delivery.failure-rate}
 * (0.0 to 1.0) makes it fail randomly, to exercise Temporal's retries.
 */
@ApplicationScoped
public class SimulatedDeliveryClient implements DeliveryClient {

    @ConfigProperty(name = "app.delivery.failure-rate", defaultValue = "0.0")
    double failureRate;

    @Override
    public void deliver(UUID invoiceId, String signedXml) {
        if (ThreadLocalRandom.current().nextDouble() < failureRate) {
            throw new IllegalStateException("recipient unavailable");
        }
        Log.infof("Delivered invoice %s (%d bytes)", invoiceId, signedXml.length());
    }
}
