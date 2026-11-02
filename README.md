# e-invoicing

Modules: `invoice-domain`, `invoice-crypto`, `invoice-workflows` (Temporal workflow and activities),
`invoice-service` (Quarkus REST API, Kafka, Postgres, and the Temporal worker).

## Prerequisites

- JDK 21+ (the Gradle wrapper is included)
- Docker with Compose

## 1. Start the infrastructure

```bash
docker compose up -d
```

| Service     | Address                                      |
|-------------|----------------------------------------------|
| Postgres    | `localhost:5432` (db/user/password: `einvoicing`) |
| Kafka       | `localhost:9092`                             |
| Temporal    | `localhost:7233`                             |
| Temporal UI | http://localhost:8233                        |

Check that everything is up with `docker compose ps`. Temporal waits for Postgres to be healthy.

## 2. Start the application

The `invoice-service` runs the REST API, the Kafka consumer, the outbox relay and the Temporal worker
(task queue `invoice-tasks`) in a single process.

```bash
# Linux / macOS / Git Bash
./gradlew :invoice-service:quarkusDev

# Windows (PowerShell / cmd)
.\gradlew.bat :invoice-service:quarkusDev
```

The API listens on http://localhost:8080. Flyway migrations run on startup.

## 3. Try it

Submit an invoice through REST:

```bash
curl -X POST http://localhost:8080/invoices \
  -H "Content-Type: application/json" \
  -d '{
    "number": "INV-001",
    "issueDate": "2026-06-01",
    "issuer":   {"name": "ACME Lda",  "taxId": "PT500000000", "countryCode": "PT"},
    "customer": {"name": "Client SA", "taxId": "ES600000000", "countryCode": "ES"},
    "currency": "EUR",
    "lines": [{"description": "Widget", "quantity": 3, "unitPrice": 19.99, "taxRatePercent": 23}]
  }'
```

Or through Kafka (same JSON, topic `invoices.submitted`):

```bash
docker compose exec -it kafka /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 --topic invoices.submitted
```

Then paste the JSON as a single line and press Enter.

Kafka topics: `invoices.submitted` (in), `invoices.events` (every invoice state change),
`workflow.results` (one message when a workflow ends, with outcome DELIVERED or REJECTED and the reason).
Watch the results with:

```bash
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic workflow.results --from-beginning
```

Other endpoints: `GET /invoices/{id}`, `POST /invoices/{id}/cancel`.
Watch the workflow run (workflow id = invoice id) in the Temporal UI at http://localhost:8233.
A Postman collection is in `postman/`.

## Tests

```bash
./gradlew test                              # default run, skips tests tagged "slow"
./gradlew :invoice-workflows:test -PslowTests   # also runs them (~30s crash-recovery test)
```

Running a test from IntelliJ's gutter icon always runs it, tag or not.

## Stop

```bash
docker compose down        # keep data
docker compose down -v     # also wipe Postgres and Kafka volumes
```
