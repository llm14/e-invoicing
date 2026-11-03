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

## Signing: process and code map

How an invoice becomes a signed XML document, step by step, and which code does each step. Paths are
relative to `invoice-crypto/src/main/java/com/example/einvoicing/crypto/` unless stated otherwise.

The private key lives in a system we cannot read (AWS KMS in production). The application sends
bytes to be signed and gets a signature back; the key never enters the JVM. The code that does this is
in place and tested against an in-memory fake (`FakeKms`); the real KMS adapter is not written yet.

### Phase 1: operator setup (once)

| Process step | Code | Status |
|---|---|---|
| Create the key in KMS | none (an AWS action) | outside the code |
| Read the public key from KMS | `CsrBuilder.publicKeyFromDer` parses what `kms:GetPublicKey` returns; the call itself is not written | half done |
| Build the CSR, signed by KMS | `CsrBuilder.createPem`; internally `RemoteContentSigner.getSignature()` calls `RemoteSigner.sign(...)` for the one signature a CSR needs | done |
| CA issues the certificate | none (external); tests use `TestPki.issueFromRequest` as a stand-in CA | outside the code |
| Configure key ID, region, chain | `SignerProducer.xmlSigner()` in `invoice-service` supports only a local keystore or an ephemeral key today | **not built** |
| Build the signing identity | `SigningIdentity.remote(signer, chain)` creates a `RemotePrivateKey`, whose constructor installs the JDK plug-in via `RemoteSignatureProvider.ensureInstalled()` | done |
| Verify the setup when the app starts | `SigningStartupCheck` (`invoice-service`) calls `SigningSelfTest.check`: signs a probe document with the real signer and validates it against the trust store (`app.signing.truststore`, `app.signing.truststore-password`). A failure stops startup. Skipped, with a warning, when no trust store is set | done |

### Phase 2: every invoice (automatic)

| Process step | Code |
|---|---|
| User submits JSON (REST or Kafka) | REST resource / Kafka consumer in `invoice-service`, which starts the workflow |
| Validate business rules | `InvoiceActivitiesImpl.validate` |
| Build the XML | `InvoiceXmlBuilder.build` |
| Sign step | `InvoiceActivitiesImpl.sign`, which calls `signer.sign(xmlBuilder.build(...))` |
| Fingerprint and sign | `EnvelopedXmlSigner.sign` builds the XMLDSig structure (canonicalization, SHA-256 digest, `RSA_SHA256`); the JDK computes the digest |
| Ask KMS to sign | `EnvelopedXmlSigner` hands the `RemotePrivateKey` to the JDK, which finds `RemoteSignatureProvider.RemoteSignature.engineSign()`; that calls `RemoteSigner.sign(SignedInfo bytes)`, where a KMS adapter would call `kms:Sign` |
| Attach signature and certificate | same method, using `SigningIdentity.chain()` for `KeyInfo/X509Data` |
| Deliver | `InvoiceActivitiesImpl.deliver` sends the stored signed XML |

Call chain of the signing step:

```
InvoiceActivitiesImpl.sign
  -> EnvelopedXmlSigner.sign            (builds the Signature structure)
    -> JDK XMLDSig                      (canonicalize + digest)
      -> RemoteSignature.engineSign     (our JCA plug-in)
        -> RemoteSigner.sign            (interface)
          -> [KmsRemoteSigner]          <- missing piece; FakeKms in tests
```

Only the small `SignedInfo` block (digest plus algorithm details) is sent to the remote signer. The
invoice text never leaves the application.

### Phase 3: validation (what the recipient does)

| Process step | Code |
|---|---|
| Entry point | `EnvelopedXmlSignatureValidator.validate`: requires exactly one signature covering the whole document |
| Pick the verification key | `X509ChainKeySelector.select` takes the key from the certificate in the XML, only after the chain is validated |
| Is the identity trusted? | `CertificateValidator.validate`: signer may sign, validity dates, chain reaches a trust anchor (revocation is not checked) |
| Where trust anchors come from | `EnvelopedXmlSignatureValidator.forTrustedCertificates`, with `KeyStoreLoader.loadTrustAnchors` reading a trust store |

The identity check runs before the signature check because the key must be trusted before it is used.

### Still missing

- `KmsRemoteSigner implements RemoteSigner`: the one class that talks to AWS (`kms:Sign` with
  `MessageType=RAW` and `RSASSA_PKCS1_V1_5_SHA_256`, and `kms:GetPublicKey`).
- `SignerProducer` does not know about KMS yet (key ID, region, certificate chain location).
- No runnable entry point for the CSR step: `CsrBuilder` is only called from tests.
- Certificate renewal and revocation checking, and a warning ahead of certificate expiry (the startup
  self-test fails only once the certificate has already expired).
