# e-invoicing

Modules: `invoice-domain`, `invoice-crypto`, `invoice-workflows` (Temporal workflow and activities),
`invoice-service` (Quarkus REST API, Kafka, Postgres, and the Temporal worker).

## Prerequisites

- JDK 21+ (the Gradle wrapper is included)
- Docker with Compose
- Optional, for the Kubernetes setup: Docker Desktop with Kubernetes enabled, `kubectl` and
  [Helm](https://helm.sh) (see "Kubernetes (Helm)"); [k9s](https://k9scli.io) is a handy terminal UI

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
./gradlew test -PslowTests                  # also runs "slow" (~30s crash-recovery test) and "temporal-server" tests
```

Tests tagged `temporal-server` need the docker-compose Temporal, because they check behaviour the
in-memory test server cannot reproduce. The `invoice-service` tests also need the docker-compose stack.

Running a test from IntelliJ's gutter icon always runs it, tag or not.

## Stop

```bash
docker compose down        # keep data
docker compose down -v     # also wipe Postgres and Kafka volumes
```

## Kubernetes (Helm)

An alternative to docker compose: run the service and its dependencies in a local Kubernetes cluster
(Docker Desktop's built-in one), closer to a production deployment. Two Helm charts in `helm/`:

| Chart | What it deploys |
|---|---|
| `helm/invoice-service` | The service: Deployment (startup, liveness and readiness probes on `/q/health/*`), Service, ConfigMap and Secret. Optional signing keystore mount |
| `helm/local-infra` | Local-only dependencies: Postgres (StatefulSet with a 1Gi volume), Kafka, Temporal and the Temporal UI. Single replicas, plaintext credentials, **not for production** |

`invoice-service` does not deploy Postgres, Kafka or Temporal. Point it at them through values
(`database.url`, `kafka.bootstrapServers`, `temporal.target`). `helm/invoice-service/values-local.yaml`
points it at the in-cluster services created by `local-infra` (`postgres:5432`, `kafka:9092`, `temporal:7233`).

### Prerequisites

- Docker Desktop with Kubernetes enabled (Settings > Kubernetes). Check with `kubectl get nodes`.
- Helm 4 (`winget install Helm.Helm`). Open a new terminal afterwards so the PATH is picked up.

### 1. Build the image

The repository has no Dockerfile or container-image extension, so build the image from the Quarkus
fast-jar. The chart expects `invoice-service:1.0.0-SNAPSHOT` (`image.repository` and the chart's `appVersion`).
Docker Desktop's cluster sees images built locally, so there is nothing to push.

```bash
./gradlew :invoice-service:quarkusBuild -x test

cat > invoice-service/build/quarkus-app/Dockerfile <<'EOF'
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY lib/ lib/
COPY app/ app/
COPY quarkus/ quarkus/
COPY quarkus-run.jar quarkus-run.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "quarkus-run.jar"]
EOF

docker build -t invoice-service:1.0.0-SNAPSHOT invoice-service/build/quarkus-app
```

`build/` is git-ignored and `gradlew clean` removes the Dockerfile, so recreate it after a clean.

### 2. Install

Install the infrastructure first and wait for it, then the service:

```bash
helm install infra helm/local-infra -n einvoicing-test --create-namespace --wait
helm install invoice helm/invoice-service -n einvoicing-test \
  -f helm/invoice-service/values-local.yaml --wait

kubectl get pods -n einvoicing-test
```

Expect five pods, all `Running`: `invoice-invoice-service`, `postgres-0`, `kafka`, `temporal`, `temporal-ui`.
Temporal may restart once or twice when the whole stack starts at the same time (its setup image
exits if its own server is not answering yet). It settles by itself.

### 3. Reach it from your machine

With the local values the charts publish three LoadBalancer Services, which Docker Desktop binds to
localhost. No port-forward is needed:

| What | Address |
|---|---|
| The service (REST API) | http://localhost:8080 |
| Temporal UI | http://localhost:8233 |
| Postgres (db/user/password: `einvoicing`) | `localhost:5433` |

They come from `service.type: LoadBalancer` in `helm/invoice-service/values-local.yaml` and the `expose`
block of `helm/local-infra/values.yaml` (`expose.enabled`, `postgresPort`, `temporalUiPort`). The in-cluster
Services (`postgres:5432`, `temporal-ui:8080`) are unchanged, because other pods use them.

- Docker Desktop binds these ports on all interfaces, not only localhost. Postgres is published with a
  well-known password, so check that your firewall blocks inbound connections to 5433, or set
  `expose.enabled=false` and use `kubectl port-forward svc/postgres 5433:5432 -n einvoicing-test` instead.
- Ports 8080 and 8233 clash with `quarkusDev` and with docker compose's Temporal UI. Stop those first, or
  change the ports in the values.
- This relies on Docker Desktop. On other clusters a LoadBalancer Service stays `<pending>`; use
  `kubectl port-forward` (or `Shift+F` on the pod in k9s) there.

The service is a REST API running with the production profile: there is no Quarkus Dev UI or Swagger
page. Use `/q/health`, `/q/health/ready` and `GET /invoices/{id}` in a browser, and the `curl` command from
"3. Try it" or the Postman collection to submit invoices. Follow the logs with
`kubectl logs -f deploy/invoice-invoice-service -n einvoicing-test` (add `--tail=100`, or name the pod
if a rollout is running). Workflows are in the Temporal UI.

### Day to day

```bash
# change values or the chart
helm upgrade invoice helm/invoice-service -n einvoicing-test -f helm/invoice-service/values-local.yaml
helm history invoice -n einvoicing-test
helm rollback invoice 1 -n einvoicing-test

# new image with the same tag: rebuild it (step 1), then restart the pod
kubectl rollout restart deploy/invoice-invoice-service -n einvoicing-test

# remove
helm uninstall invoice -n einvoicing-test
helm uninstall infra -n einvoicing-test
kubectl delete pvc data-postgres-0 -n einvoicing-test    # wipes the database
kubectl delete ns einvoicing-test                        # everything, including the volume
```

`helm uninstall` leaves the Postgres volume behind, so the data survives a reinstall.

Checks without a cluster: `helm lint helm/invoice-service`, `helm lint helm/local-infra` and
`helm template t helm/invoice-service`.

### Start and stop the cluster

Docker Desktop's Kubernetes has no start or stop command of its own. Quitting Docker Desktop stops
Docker and Kubernetes together; pods, releases and the Postgres volume come back when it restarts.
To free resources without stopping the cluster:

```bash
kubectl scale deploy --all --replicas=0 -n einvoicing-test
kubectl scale statefulset postgres --replicas=0 -n einvoicing-test
# back up: Postgres first, wait for it, then the rest
kubectl scale statefulset postgres --replicas=1 -n einvoicing-test
kubectl scale deploy --all --replicas=1 -n einvoicing-test
```

Unticking "Enable Kubernetes" or "Reset Kubernetes Cluster" in Docker Desktop discards the cluster and
its workloads; redeploy with the steps above.

### Chart values

Main `helm/invoice-service/values.yaml` settings:

| Value | Default | Meaning |
|---|---|---|
| `image.repository`, `image.tag` | `invoice-service`, chart `appVersion` | Image to run |
| `replicaCount` | `1` | Keep at 1 unless a shared signing keystore is configured: without a keystore each replica generates its own ephemeral signing key |
| `database.url`, `database.username` | `jdbc:postgresql://postgres:5432/einvoicing`, `einvoicing` | Become `DB_URL` and `DB_USER` |
| `database.password`, `database.existingSecret` | `einvoicing`, empty | The password goes in a Secret; set `existingSecret` to use your own |
| `kafka.bootstrapServers` | `kafka:9092` | Becomes `KAFKA_BOOTSTRAP_SERVERS` |
| `temporal.target` | `temporal:7233` | Becomes `TEMPORAL_TARGET` |
| `extraEnv` | empty | Extra variables. Quarkus maps `APP_SWEEP_INTERVAL` to `app.sweep.interval`, and so on |
| `signing.enabled` | `false` | Mounts a PKCS#12 keystore (and optional truststore) from existing Secrets; requires `signing.existingSecret` and `signing.passwordSecret` |
| `terminationGracePeriodSeconds` | `45` | Must stay above `quarkus.shutdown.timeout` (35s) so running activities can drain |

Why the in-cluster Kafka is separate from the compose one: compose Kafka advertises `localhost:9092`,
which a pod cannot use, and the cluster's Postgres, Kafka and Temporal hold their own data, separate from
the compose containers. `local-infra` pods set `enableServiceLinks: false`; without it Kubernetes injects
variables such as `TEMPORAL_UI_PORT=tcp://...` that break the Temporal UI's configuration.

## Workflow behaviour

What the Temporal workflow does when things go wrong (`InvoiceWorkflowImpl`):

| Situation | What happens |
|---|---|
| Validation or signing fails for an infrastructure reason (database, key service) | Retried without an attempt limit (backoff from 1s up to 1 minute). The invoice waits in `SIGNING` and is not rejected |
| The invoice breaks our business rules | `InvoiceInvalid` failure: not retried, invoice rejected |
| Delivery fails because the recipient is unreachable | Retried without an attempt limit (backoff from 2s up to 1 minute) |
| The recipient refuses the invoice for good | `DeliveryRefused` failure (`DeliveryRefusedException` in the delivery client): not retried, invoice rejected |
| `POST /invoices/{id}/cancel` during validation or signing | Interrupts the step, including while it is retrying, and rejects the invoice. An attempt that is already running finishes first |
| Cancel once delivery has started | No effect |

Every failed attempt is logged with its attempt number. While a step retries, the Temporal UI
(http://localhost:8233) shows its pending activity, attempt count and last failure.

### Recovery sweep

An invoice is stored before its workflow is started, and the two cannot be committed together. If
Temporal is down at that moment, or a workflow later dies, the invoice would stay unfinished forever.
A Temporal Schedule (`invoice-sweep`, created or updated at startup by `SweepSchedule`) runs
`InvoiceSweepWorkflow` every minute. It starts the workflow of every unfinished invoice older than
`app.sweep.min-age` that has no running workflow.

- A workflow id can be reused only after a run that failed, timed out or was terminated (`ALLOW_DUPLICATE_FAILED_ONLY`), never after one that completed.
- Terminating a workflow in the Temporal UI therefore does not stop an invoice for good: the sweep restarts it. To stop an invoice, cancel it.
- If Temporal is down at startup, the app still starts and keeps retrying to create the schedule in the background.

| Property | Default | Meaning |
|---|---|---|
| `app.sweep.interval` | `PT1M` | How often the sweep runs |
| `app.sweep.min-age` | `PT2M` | Invoices younger than this are skipped |
| `app.sweep.batch-size` | `500` | Most invoices restarted per run |
| `app.temporal.shutdown-drain` | `PT30S` | How long a stopping worker waits for running activities to report |
| `app.delivery.refusal-rate` | `0.0` | Simulated recipient: chance of refusing an invoice for good |

## Temporal workflows: process and code map

How an invoice moves through Temporal, step by step, and which code does each step. Paths are
relative to `invoice-workflows/src/main/java/com/example/einvoicing/workflow/` (workflow and activity
interfaces) or `invoice-service/src/main/java/com/example/einvoicing/service/` (everything else).

Two kinds of code, kept in two modules on purpose:

- **Workflow code** (`invoice-workflows`) only decides the order of steps and what to do when one
  fails: `InvoiceWorkflowImpl`, `InvoiceSweepWorkflowImpl`. It has no database, no HTTP and no Kafka,
  because Temporal replays it from its history and it must behave the same every time.
- **Activity code** (`invoice-service`) does the real work and the side effects:
  `InvoiceActivitiesImpl`, `SweepActivitiesImpl`. Temporal may run an activity more than once, so each
  one first checks the invoice's current status and does nothing if its step already happened.

The Temporal server stores each workflow's history and hands tasks to workers. The worker runs inside
the `invoice-service` process, polling the task queue `invoice-tasks`.

### Phase 1: application start and stop

| Process step | Code | Status |
|---|---|---|
| Connect to Temporal and start the worker | `temporal/TemporalRuntime.start`: creates the client and a worker on `app.temporal.task-queue`, registers both workflows and both activity classes, then starts polling. The connection is lazy, so the app starts even if Temporal is down | done |
| Create or update the recovery schedule | `temporal/SweepSchedule.ensureInBackground`, then `createOrUpdate`: creates the `invoice-sweep` schedule, or updates it if it exists. If Temporal does not answer it retries every 10 seconds in the background | done |
| Stop without losing results | `temporal/TemporalRuntime.stop`: stops polling, waits up to `app.temporal.shutdown-drain` for running activities to report, then interrupts what is left | done |

### Phase 2: every invoice (automatic)

| Process step | Code |
|---|---|
| User submits JSON (REST or Kafka) | `api/InvoiceResource.submit` or `messaging/InvoiceSubmissionConsumer.consume`, both calling `InvoiceIntake.receive` |
| Store the invoice | `InvoiceService.submit` saves it as `Received` and queues its event in the outbox |
| Start the workflow | `temporal/InvoiceWorkflows.start`: workflow id = invoice id, ID reuse only after a failed run. Returns as soon as Temporal accepts it |
| A worker picks it up | `TemporalRuntime`'s worker runs `InvoiceWorkflowImpl.process(invoiceId)`; after a restart or crash Temporal replays the recorded history to rebuild its state |
| Validate | `InvoiceWorkflowImpl.process` calls the `preDelivery` stub, which runs `InvoiceActivitiesImpl.validate` (rules in `processing/InvoiceValidator`) and saves `Validated` |
| Sign | same stub, `InvoiceActivitiesImpl.sign`; see "Signing: process and code map" for what happens inside. Saves `Signed` |
| Last chance to cancel | `InvoiceWorkflowImpl.failIfCancelled` |
| Deliver | the `delivery` stub runs `InvoiceActivitiesImpl.deliver`, which calls `processing/DeliveryClient` (`SimulatedDeliveryClient`) and saves `Delivered` |
| Report the result | the `results` stub runs `InvoiceActivitiesImpl.recordResult`, which queues one `workflow.results` event in the outbox; `messaging/OutboxRelay` publishes it to Kafka |

Each step is a separate task on the Temporal server, so a crash between steps loses nothing: the
workflow resumes at the next step, and a step that was running is retried.

Call chain of one invoice:

```
InvoiceResource.submit / InvoiceSubmissionConsumer.consume
  -> InvoiceIntake.receive
    -> InvoiceService.submit             (Postgres: invoice + outbox row)
    -> InvoiceWorkflows.start            (Temporal server records the workflow)
         ... a workflow task waits on the 'invoice-tasks' queue ...
      -> worker in TemporalRuntime
        -> InvoiceWorkflowImpl.process   (decides the order; no side effects)
          -> InvoiceActivitiesImpl.validate
          -> InvoiceActivitiesImpl.sign
          -> InvoiceActivitiesImpl.deliver
          -> InvoiceActivitiesImpl.recordResult
```

### Phase 3: when something goes wrong

| Situation | Code | What happens |
|---|---|---|
| Database or key service fails during validate or sign | the `preDelivery` stub in `InvoiceWorkflowImpl`: retry options with no attempt limit, backoff 1s to 1 minute | The step retries until it works; the invoice waits in `SIGNING` |
| The invoice breaks a business rule | `InvoiceActivitiesImpl.validate` throws a non-retryable `InvoiceInvalid` failure (`InvoiceActivities.INVALID_INVOICE`) | `process` catches the `ActivityFailure` and calls `compensate`, which runs `InvoiceActivitiesImpl.reject`; the invoice is `Rejected` |
| The recipient is unreachable | the `delivery` stub: no attempt limit, backoff 2s to 1 minute | `deliver` retries until the recipient answers |
| The recipient refuses the invoice | `DeliveryClient` throws `DeliveryRefusedException`; `InvoiceActivitiesImpl.deliver` turns it into the non-retryable `DeliveryRefused` failure (`InvoiceActivities.DELIVERY_REFUSED`) | Same path as an invalid invoice: `compensate`, then `Rejected` |
| User cancels during validate or sign | `InvoiceResource.cancel` -> `InvoiceWorkflows.cancel` sends a signal -> `InvoiceWorkflowImpl.cancel` cancels the scope that wraps validate and sign | The step is interrupted, even while retrying (an attempt that is already running finishes first); then `compensate` |
| User cancels once delivery has started | `InvoiceWorkflowImpl.cancel` still sets its flag, but the only check of it (`failIfCancelled`) is before delivery | No effect |
| The worker dies mid-step | the activity's start-to-close timeout (30s or 60s) in `InvoiceWorkflowImpl` | Temporal runs the step again on another worker; this is why activities check the status first. Test: `InvoiceWorkflowTest` (tag `slow`) |
| The app is restarted | `TemporalRuntime.stop` | Running steps get up to 30 seconds to report; the workflow carries on after the restart |

Whatever happens, `process` ends by running `recordResult`, so every workflow publishes exactly one
result (`DELIVERED` or `REJECTED`, with the reason).

### Phase 4: recovery (every minute, automatic)

| Process step | Code |
|---|---|
| The schedule fires | the Temporal Schedule `invoice-sweep` (`SweepSchedule`); if a run is still going, the next tick is skipped |
| Run the sweep | `InvoiceSweepWorkflowImpl.sweep` calls `SweepActivitiesImpl.restartStuckInvoices` |
| Find unfinished invoices | `InvoiceService.unfinishedReceivedBefore` -> `persistence/InvoiceRepository.findUnfinishedIdsReceivedBefore`: not `Delivered` or `Rejected`, older than `app.sweep.min-age`, at most `app.sweep.batch-size` |
| Restart the missing workflows | `InvoiceWorkflows.startIfNotRunning`: Temporal answers "already started" for a running or completed workflow, which is ignored; a failed or terminated one is started again and resumes at the right step |

### Watching it run

- Temporal UI (http://localhost:8233): the workflow with id = invoice id, its history, and for a step
  that is retrying, its attempt count and last failure.
- Application log: one warning per failed attempt, with the attempt number (`InvoiceActivitiesImpl.attempt`).
- Query `InvoiceWorkflow.getStatus` returns `VALIDATING`, `SIGNING`, `DELIVERING`, `DELIVERED` or
  `REJECTED`. Only tests use it; the REST API reads the status from Postgres.

### Still missing

- The real recipient may or may not deduplicate by invoice id. Until that is confirmed, a delivery
  repeated after a crash could reach the recipient twice.
- No workflow versioning and no replay test (deliberately left out): changing `InvoiceWorkflowImpl`
  while workflows are running could break them.
- Nothing alerts when an invoice has been retrying for a long time; only the log and the Temporal UI show it.
- Terminating a workflow in the Temporal UI does not stop an invoice: the sweep restarts it. Use cancel.

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
