package com.example.einvoicing.service.messaging;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

/** Needs the docker-compose stack (Postgres, Kafka and Temporal) to be running. */
@QuarkusTest
class KafkaFlowTest {

    private static final String BOOTSTRAP = "localhost:9092";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static String invoiceJson(String number, String unitPrice) {
        return """
                {
                  "number": "%s",
                  "issueDate": "2026-06-01",
                  "issuer": {"name": "ACME Lda", "taxId": "PT500000000", "countryCode": "PT"},
                  "customer": {"name": "Client SA", "taxId": "ES600000000", "countryCode": "ES"},
                  "currency": "EUR",
                  "lines": [{"description": "Widget", "quantity": 3, "unitPrice": %s, "taxRatePercent": 23}]
                }
                """.formatted(number, unitPrice);
    }

    private static void publish(String topic, String value) throws Exception {
        var props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        try (var producer = new KafkaProducer<String, String>(props)) {
            producer.send(new ProducerRecord<>(topic, value)).get();
        }
    }

    private static KafkaConsumer<String, String> consumer(String topic) {
        var props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        var consumer = new KafkaConsumer<String, String>(props);
        consumer.subscribe(List.of(topic));
        return consumer;
    }

    /** Collects the events of one invoice from invoices.events until it reaches a final state. */
    private static List<JsonNode> eventsUntilFinished(String number) throws Exception {
        var events = new ArrayList<JsonNode>();
        var deadline = Instant.now().plusSeconds(45);
        try (var consumer = consumer("invoices.events")) {
            while (Instant.now().isBefore(deadline)) {
                for (var record : consumer.poll(Duration.ofMillis(500))) {
                    var event = JSON.readTree(record.value());
                    if (number.equals(event.get("number").asText())) {
                        assertThat(record.key()).isEqualTo(event.get("invoiceId").asText());
                        events.add(event);
                    }
                }
                if (!events.isEmpty()) {
                    var last = events.get(events.size() - 1).get("status").asText();
                    if (last.equals("DELIVERED") || last.equals("REJECTED")) {
                        return events;
                    }
                }
            }
        }
        throw new AssertionError("Invoice " + number + " did not finish in time; events: " + events);
    }

    private static List<String> statuses(List<JsonNode> events) {
        return events.stream().map(e -> e.get("status").asText()).toList();
    }

    @Test
    void invoiceFromKafkaIsProcessedAndEveryStateChangeIsPublished() throws Exception {
        var number = "KAFKA " + UUID.randomUUID();

        publish("invoices.submitted", invoiceJson(number, "19.99"));

        assertThat(statuses(eventsUntilFinished(number)))
                .containsExactly("RECEIVED", "VALIDATED", "SIGNED", "DELIVERED");
    }

    @Test
    void invoiceRejectedByTheWorkflowPublishesTheReason() throws Exception {
        var number = "KAFKA " + UUID.randomUUID();

        publish("invoices.submitted", invoiceJson(number, "0"));

        var events = eventsUntilFinished(number);
        assertThat(statuses(events)).containsExactly("RECEIVED", "REJECTED");
        assertThat(events.get(1).get("reason").asText()).contains("gross total must be positive");
    }

    @Test
    void invoiceSubmittedOverRestAlsoPublishesEvents() throws Exception {
        var number = "REST " + UUID.randomUUID();

        given().contentType(ContentType.JSON)
                .body(invoiceJson(number, "19.99"))
                .post("/invoices")
                .then()
                .statusCode(201);

        assertThat(statuses(eventsUntilFinished(number)))
                .containsExactly("RECEIVED", "VALIDATED", "SIGNED", "DELIVERED");
    }

    @Test
    void unparseableMessageGoesToTheDeadLetterTopic() throws Exception {
        var garbage = "not json " + UUID.randomUUID();

        publish("invoices.submitted", garbage);

        var deadline = Instant.now().plusSeconds(30);
        try (var consumer = consumer("invoices.submitted.dlq")) {
            while (Instant.now().isBefore(deadline)) {
                for (var record : consumer.poll(Duration.ofMillis(500))) {
                    if (garbage.equals(record.value())) {
                        return;
                    }
                }
            }
        }
        throw new AssertionError("Message did not reach invoices.submitted.dlq");
    }
}
