package com.example.einvoicing.service.api;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Needs the docker-compose stack (Postgres and Temporal) to be running. */
@QuarkusTest
class InvoiceResourceTest {

    private static String uniqueNumber() {
        return "FT " + UUID.randomUUID();
    }

    private static String invoiceJson(String number, String unitPrice) {
        return """
                {
                  "number": "%s",
                  "issueDate": "2026-06-01",
                  "dueDate": "2026-07-01",
                  "issuer": {"name": "ACME Lda", "taxId": "PT500000000", "countryCode": "PT"},
                  "customer": {"name": "Client SA", "taxId": "ES600000000", "countryCode": "ES"},
                  "currency": "EUR",
                  "lines": [
                    {"description": "Widget", "quantity": 3, "unitPrice": %s, "taxRatePercent": 23}
                  ]
                }
                """.formatted(number, unitPrice);
    }

    private static String submit(String body) {
        return given().contentType(ContentType.JSON)
                .body(body)
                .post("/invoices")
                .then()
                .statusCode(201)
                .extract()
                .header("Location");
    }

    private static void awaitStatus(String location, String status) {
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> given().get(location).then().body("status", equalTo(status)));
    }

    @Test
    void submitReturnsTheNewInvoice() {
        given().contentType(ContentType.JSON)
                .body(invoiceJson(uniqueNumber(), "19.99"))
                .post("/invoices")
                .then()
                .statusCode(201)
                .body("status", equalTo("RECEIVED"))
                .body("netTotal", equalTo(59.97f))
                .body("taxTotal", equalTo(13.79f))
                .body("grossTotal", equalTo(73.76f));
    }

    @Test
    void submittedInvoiceIsProcessedToDelivered() {
        var location = submit(invoiceJson(uniqueNumber(), "19.99"));

        awaitStatus(location, "DELIVERED");
        given().get(location).then().body("currency", equalTo("EUR"));
    }

    @Test
    void invoiceFailingBusinessRulesIsRejectedByTheWorkflow() {
        var location = submit(invoiceJson(uniqueNumber(), "0"));

        awaitStatus(location, "REJECTED");
        given().get(location).then().body("rejectionReason", containsString("gross total must be positive"));
    }

    @Test
    void cancellingAFinishedInvoiceIsAConflict() {
        var location = submit(invoiceJson(uniqueNumber(), "19.99"));
        awaitStatus(location, "DELIVERED");

        given().post(location + "/cancel").then().statusCode(409);
    }

    @Test
    void cancellingAnUnknownInvoiceIs404() {
        given().post("/invoices/" + UUID.randomUUID() + "/cancel").then().statusCode(404);
    }

    @Test
    void duplicateNumberForSameIssuerIsRejected() {
        var body = invoiceJson(uniqueNumber(), "19.99");
        given().contentType(ContentType.JSON).body(body).post("/invoices").then().statusCode(201);
        given().contentType(ContentType.JSON).body(body).post("/invoices").then().statusCode(409);
    }

    @Test
    void invalidInvoiceIsRejectedWith400() {
        given().contentType(ContentType.JSON)
                .body(invoiceJson(uniqueNumber(), "19.99").replace("\"quantity\": 3", "\"quantity\": 0"))
                .post("/invoices")
                .then()
                .statusCode(400)
                .body("error", containsString("quantity"));
    }

    @Test
    void unknownInvoiceIs404() {
        given().get("/invoices/" + UUID.randomUUID()).then().statusCode(404);
    }
}
