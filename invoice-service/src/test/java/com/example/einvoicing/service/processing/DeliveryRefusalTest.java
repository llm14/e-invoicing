package com.example.einvoicing.service.processing;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Needs the docker-compose stack (Postgres and Temporal) to be running. */
@QuarkusTest
@TestProfile(DeliveryRefusalTest.RecipientRefusesEverything.class)
class DeliveryRefusalTest {

    public static class RecipientRefusesEverything implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("app.delivery.refusal-rate", "1.0");
        }
    }

    @Test
    void invoiceTheRecipientRefusesIsRejectedWithTheReasonAndNotRetried() {
        var location = given().contentType(ContentType.JSON)
                .body("""
                        {
                          "number": "REFUSED %s",
                          "issueDate": "2026-06-01",
                          "issuer": {"name": "ACME Lda", "taxId": "PT500000000", "countryCode": "PT"},
                          "customer": {"name": "Client SA", "taxId": "ES600000000", "countryCode": "ES"},
                          "currency": "EUR",
                          "lines": [{"description": "Widget", "quantity": 3, "unitPrice": 19.99, "taxRatePercent": 23}]
                        }
                        """.formatted(UUID.randomUUID()))
                .post("/invoices")
                .then()
                .statusCode(201)
                .extract()
                .header("Location");

        // A refusal is not retried; with retries (2s first backoff, then growing) this would take longer.
        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> given().get(location)
                        .then()
                        .body("status", equalTo("REJECTED"))
                        .body("rejectionReason", containsString("recipient refused the invoice")));
    }
}
