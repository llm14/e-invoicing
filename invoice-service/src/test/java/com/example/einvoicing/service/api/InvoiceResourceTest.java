package com.example.einvoicing.service.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.containsString;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
class InvoiceResourceTest {

    private static String invoiceJson(String number, String taxId) {
        return """
                {
                  "number": "%s",
                  "issueDate": "2026-06-01",
                  "dueDate": "2026-07-01",
                  "issuer": {"name": "ACME Lda", "taxId": "%s", "countryCode": "PT"},
                  "customer": {"name": "Client SA", "taxId": "ES600000000", "countryCode": "ES"},
                  "currency": "EUR",
                  "lines": [
                    {"description": "Widget", "quantity": 3, "unitPrice": 19.99, "taxRatePercent": 23}
                  ]
                }
                """.formatted(number, taxId);
    }

    @Test
    void submitThenFetch() {
        var location = given().contentType(ContentType.JSON)
                .body(invoiceJson("FT 1", "PT500000001"))
                .post("/invoices")
                .then()
                .statusCode(201)
                .body("status", equalTo("RECEIVED"))
                .body("netTotal", equalTo(59.97f))
                .body("taxTotal", equalTo(13.79f))
                .body("grossTotal", equalTo(73.76f))
                .extract()
                .header("Location");

        given().get(location)
                .then()
                .statusCode(200)
                .body("number", equalTo("FT 1"))
                .body("status", equalTo("RECEIVED"))
                .body("currency", equalTo("EUR"));
    }

    @Test
    void duplicateNumberForSameIssuerIsRejected() {
        var body = invoiceJson("FT 2", "PT500000002");
        given().contentType(ContentType.JSON).body(body).post("/invoices").then().statusCode(201);
        given().contentType(ContentType.JSON).body(body).post("/invoices").then().statusCode(409);
    }

    @Test
    void invalidInvoiceIsRejectedWith400() {
        given().contentType(ContentType.JSON)
                .body(invoiceJson("FT 3", "PT500000003").replace("\"quantity\": 3", "\"quantity\": 0"))
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
