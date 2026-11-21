# Implementation Plan – Spec 001

## Tech Stack

| Concern | Choice |
|---|---|
| Language / build | Java 21, Maven (multi-module) |
| Framework | Spring Boot 3.5.x (same line as `../homehub`) |
| Workflow engine | Temporal Java SDK (`temporal-spring-boot-starter`); server at `localhost:7233`, namespace `default` |
| DB | PostgreSQL 18 (`postgres:18` Docker container), JDBC via Spring `JdbcClient`, Flyway migrations |
| AWS SDK | AWS SDK for Java v2 BOM: `sqs`, `url-connection-client`. Endpoint override and per-client CA trust come from config (homehub `AwsClientConfig` pattern). |
| Trigger Lambda | Java 21 handler (`aws-lambda-java-core` + `aws-lambda-java-events`), SQS event source mapping, `java.net.http.HttpClient` |
| AWS emulation | **AWS Emulator** (official AWS, released 2026-11-11), run in Docker, for **SQS**. Lambda can't be emulated for this app (V-2, V-4) and RDS isn't real Postgres; see *Local Environment* |
| External API mocks | **WireMock 3.x** for every external API: standalone Docker container `wiremock/wiremock` locally (reuse-first, like the emulator) and in-JVM `WireMockExtension` in tests. Stubs are generated (constitution P-5). Contracts in `contracts/*.openapi.yaml`, validated with `swagger-request-validator`. |
| API conventions | RFC 9457 problem+json, springdoc OpenAPI, actor header `X-Funder-Id` (homehub OQ-1 / D-6 patterns) |
| Observability | SLF4J + JSON logs with MDC (`loanApplicationId`, `applicationVersionId`, `workflowId`); Temporal SDK metrics through Micrometer. OTel agent optional (homehub `otel-env.sh` can be ported later). |
| Money | `BigDecimal`, `MathContext.DECIMAL128`, `RoundingMode.HALF_UP` |
| Tests | JUnit 5, AssertJ, Temporal `TestWorkflowEnvironment`, Testcontainers (Postgres 18), WireMock. Functional ITs (Failsafe) run against the shared AWS Emulator container. |

## Modules

```
funding-automation/
├── funding-domain/      # pure Java: rules (TILA, Price, VIN, Income, GAP), money math, status machine — no I/O
├── funding-persistence/ # Flyway migrations, repositories
├── funding-workflows/   # workflow + activity interfaces/impls, worker config
├── funding-trigger/     # outbox relay -> SQS publisher
├── funding-trigger-lambda/ # SQS-triggered Lambda -> POST start-workflow API (shaded jar, no Spring)
├── funding-seed/        # SeedGenerator, MockStubGenerator, intent/catalog models, gates: conformance, coverage, contract, lock, docs (P-2, P-5)
├── funding-api/         # Spring REST: workflow start API, funder API, dev endpoints (submit, add version/document)
├── contracts/           # income-verification.openapi.yaml, disbursement.openapi.yaml (one per external API)
├── scripts/emulator-env.sh # ported from ../homehub: find or start the emulator, export SDK env
├── scripts/db-env.sh   # reuse or start postgres:18, export FUNDING_DB_*
├── test/data/          # scenarios/*.yaml, _baseline.yaml, mocks/<api>.catalog.yaml, gap-form-registry, rules.lock, mocks.lock, RULE-CHANGES.md
├── scripts/wiremock-env.sh # reuse a running WireMock container, else start funding-wiremock; load generated stubs
├── run-local.sh         # emulator-env + db-env + spring-boot:run (local profile)
└── Makefile             # run | down | seed-check | seed-regenerate | seed-submit-all | test-unit | test-functional | test-functional-volume
```
One Spring Boot app runs all modules locally, and profiles can split them later.

## Temporal Design

**Task queues:** `funding-orchestration` for the parent workflow, `funding-validation` for the children and DB activities, `funding-external` for external API activities, so each pool can be rate-limited separately.

**Workflow ID:** `funding-<FUNDING_ENV>-<versionId>`. `FUNDING_ENV` defaults to `local`; functional tests use `it-<runId>` (NFR-08).
- `WorkflowIdConflictPolicy.USE_EXISTING`: a duplicate start while a run is open returns that run (200 `alreadyStarted`).
- `WorkflowIdReusePolicy.ALLOW_DUPLICATE`: after a run closes, the ID can start again. This keeps a wiped and re-seeded DB, whose SERIAL ids restart, from colliding with old closed runs in local Temporal.
- Duplicate deliveries after close are stopped by the start API's DB check (409 `NOT_PENDING_REVIEW` once the version has settled).

**`FundingWorkflow.run(FundingInput{versionId, mode})`** with mode `VALIDATE` (from the trigger) or `CONTINUE` (only from `continueAsNew`):
1. `loadVersionSnapshot(versionId)` returns the latest document field values (no SSN) **plus** the current results, stips and `submitted_at`. `CONTINUE` skips to step 4 using that state. The workflow computes `expiresAt = submitted_at + funding.workflow.expire-after` once and carries it through `continueAsNew`.
2. Start the children in parallel (`Async.function` + `Promise.allOf`): `TilaCheckWorkflow`, `PriceParityWorkflow`, `VinConsistencyWorkflow`, `IncomeVerificationWorkflow`, `GapValidationWorkflow`.
   - Child ID: `<parentId>-<CHECK>-a<attempt>`.
   - Each returns a `CheckOutcome`. One child failing doesn't cancel the others.
   - A child failing *technically* (retries exhausted) becomes outcome `ERROR`.
3. `applyOutcome(versionId, outcomes)` sets APPROVED, STIPULATED or PENDING_REVIEW, and auto-satisfies stale stips (AS-8.8). It runs in one transaction guarded by `is_latest_version`. If the guard fails, the workflow completes with `SUPERSEDED` (AS-9.5).
4. Signal loop with `Workflow.await(expiresAt − Workflow.currentTimeMillis(), …)`. When that timer fires while waiting, the workflow runs `expire(versionId)`: status → `EXPIRED` (guarded by latest + non-terminal, audited), then completes (AS-8.6). It handles these signals:
   - `stipResolved()` — the API signals after its DB commit; the workflow re-reads stip state from the DB, so the signal is only a wake-up.
   - `approve(actor)`, `reject(actor, reason)`
   - `rerunChecks(Set<Check>)` — reloads the snapshot, and the attempt number goes up by one.
   - `documentsUpdated()` — re-runs all checks.
   - `retryDisbursement()`, `supersede()`
5. On APPROVED: `disburse(versionId)`. An in-flight disbursement is never cancelled by expiry; expiry is checked after it returns. with idempotency key = workflow ID and amount = RISC `amount_financed`, then `markFunded`. The activity first re-checks in the DB, in one transaction, that the version is latest, `APPROVED` and has no PENDING stip (AS-8.1). This closes the race with a manual stip or a reject.
6. Query `getState()` returns the outcomes, the attempt per check, and the step the workflow is waiting on.
7. Size: a 30-day wait with a few signals stays far below history limits. If signals pass 1,000, `continueAsNew` with mode `CONTINUE`.

**Funder actions** (approve, reject, stip changes, re-run, retry) go through the API, which:
1. checks the version is latest and not terminal (`FUNDED`, `REJECTED`, `EXPIRED` → 409; `VERSION_EXPIRED` for expired);
2. writes to the DB, guarded and audited;
3. then sends a plain **signal** to `funding-<env>-<versionId>`. No resume: a closed run means the version is terminal (C-11).

**Versioning (NFR-07):** workflow code changes are guarded with `Workflow.getVersion`. CI replays recorded histories from `src/test/resources/histories/` with `WorkflowReplayer`.

**Child workflows:** each one is `activity: persistResult + createStips` around a call to a **pure rule** from `funding-domain`. The income child first calls the `verifyIncome` activity on `funding-external`. Business FAIL is a return value, not an exception, so Temporal never retries it.

**Resubmission:** when the start-workflow API receives a request for V2, it sends `supersede` to V1's workflow (looked up from the DB) before starting V2's. If V1 has no open run, that's ignored (AS-9.6). Otherwise V1 cancels its children and completes `SUPERSEDED`. Activity writes check `is_latest_version` in the same transaction (AS-9.3), which covers the race where V2 is created while V1's checks are in flight.

## Trigger Path

```
status flip / new latest document -> DB trigger -> outbox_events -> OutboxRelay (@Scheduled 1s, SELECT … FOR UPDATE SKIP LOCKED)
  -> SQS `funding-pending-review` (visibility timeout 60s, DLQ after 5 receives)
  -> event source mapping (batch 10, ReportBatchItemFailures)        [locally: LocalLambdaRunner]
  -> Lambda `funding-trigger`  --HTTP-->  POST /api/v1/funding-workflows  (Spring app, same process as Temporal workers)
                                              -> supersede previous latest version's workflow (if running)
                                              -> APPLICATION_VERSION_PENDING_REVIEW: WorkflowClient.start(FundingWorkflow, VALIDATE)
                                              -> DOCUMENT_VERSION_ADDED: signal documentsUpdated (no open run → 409 NO_OPEN_RUN, ack)
```

The relay marks a row published only after SQS accepts it. A crash between the send and the mark causes a duplicate message, which the start API absorbs. The relay also deletes published rows older than 7 days.

A Temporal workflow can't serve HTTP itself. The **start-workflow API** is a REST controller in the worker's Spring Boot app, and it uses the Temporal `WorkflowClient`.

### Start-workflow API (contract)

`POST /api/v1/funding-workflows`. The header `X-Trigger-Api-Key` must match `FUNDING_TRIGGER_API_KEY`.

```json
{ "eventType": "APPLICATION_VERSION_PENDING_REVIEW", "loanApplicationId": "LA-1001", "applicationVersionId": 42, "documentId": null, "sourceMessageId": "<sqs message id>" }
```

| Response | When | Lambda action |
|---|---|---|
| `202 {workflowId, runId}` | Workflow started | ack |
| `200 {workflowId, runId, alreadyStarted:true}` | A run is already open (`USE_EXISTING` returned it: duplicate delivery) | ack |
| `409 {reason:"NOT_LATEST_VERSION"\|"NOT_PENDING_REVIEW"}` | Stale message: version superseded or status changed | ack (log at warn) |
| `400` | Malformed payload | ack and log at error. Will never succeed, so don't retry until the DLQ. |
| `401` | Bad API key | fail item (config error, goes to the DLQ) |
| `5xx` / timeout | Temporal or DB unavailable | fail item, SQS redelivers |

`GET /api/v1/funding-workflows/{applicationVersionId}` returns the workflow ID, run status and the `getState()` query result.

### Lambda `funding-trigger`

- **Handler:** `com.funding.trigger.lambda.SqsTriggerHandler implements RequestHandler<SQSEvent, SQSBatchResponse>`. It returns `batchItemFailures` only for items that should be retried (per the table above), so one bad message doesn't fail the batch.
- **Configuration** (env vars): `FUNDING_API_BASE_URL`, `FUNDING_TRIGGER_API_KEY`, `HTTP_TIMEOUT_MS=5000`. The Lambda timeout (30 s) must stay below the queue visibility timeout (60 s).
- **Time budget:** 10 records × 5 s each, done one after another, would exceed the 30 s timeout. The records are sent **concurrently** on virtual threads instead. Before each call the handler checks `context.getRemainingTimeInMillis()`. A record that can't finish in the remaining time is reported in `batchItemFailures` without being sent, so it's retried instead of lost.
- **Secret:** on AWS, the API key comes from Secrets Manager (cached). Locally it comes from an env var.
- **Dependencies:** no Spring and no Temporal SDK, which keeps cold starts small. It's a shaded jar built by `maven-shade-plugin`.
- **Logic:** the handler logic sits in a plain class (`TriggerProcessor`) that takes an HTTP port interface, so it can be unit-tested without Lambda.
- **Deployment:** on real AWS, infrastructure as code deploys the shaded jar (`java21`) plus an event source mapping with `ReportBatchItemFailures`. Locally the handler runs on the host through `LocalLambdaRunner`, because the emulator can't run `java21` and its Lambdas can't reach the host (V-2, V-4).

## Local Environment

- **Temporal:** existing server at `localhost:7233`.
- **AWS Emulator (SQS).** Same setup as `../homehub`: reuse its scripts and patterns, don't reinvent them.
  - **Image:** `aws-emulator:latest`, built once from `../aws-emulator/Dockerfile` (official Dockerfile, `aws-emulator-aarch64.rpm`). The image is shared across projects, so this repo has no Dockerfile.
  - **`scripts/emulator-env.sh`** (ported from homehub). **Reuse a running emulator first, and start a new one only if no existing container can be reached.**
    1. **Pick:** `EMULATOR_CONTAINER` if set, otherwise the single running `aws-emulator:latest` container. Several running is an error: choose one with `EMULATOR_CONTAINER`.
    2. **Internal port:** parse `endpoint_url` from the container's `docker logs` startup line (default 9297).
    3. **Endpoint:**
       - If the port is published (`docker port`), use `https://127.0.0.1:<host port>`.
       - If it isn't published, as with a plain `docker run aws-emulator:latest`, start the forwarder `funding-emulator-proxy` (`alpine/socat`, label `funding.managed=true`): `-p 127.0.0.1:<port>:<port>` → `<container bridge IP>:<port>`. Docker Desktop for Mac can't route to container IPs directly. TLS passes through unchanged, and the emulator's certificate is issued for `127.0.0.1`.
    4. **Credentials and CA:**
       - If `~/.aws/emulator` is bind-mounted, use the host path.
       - Otherwise `docker cp` `ca.pem`, `config` and `emulator_credentials` from `/home/emulator/.aws/emulator/<port>/` to `~/.aws/emulator/reuse-<container>/` (mode 700/600).
       - Copy again on **every** source, because the emulator generates new keys and a new CA each time it starts.
    5. **Reachability check:** `/__admin/health` returns `ok` with that CA, **and** `aws sts get-caller-identity` returns account `000000000000`. That proves the endpoint, CA and credentials all match.
    6. **Fallback:** only if 1–5 fail (no container, or it's unreachable), start a new `funding-emulator` (label `funding.managed=true`, `127.0.0.1:9297:9297`, `HOME` plus a bind mount of `~/.aws/emulator`, 2 GiB memory).
    - Then export `AWS_ENDPOINT_URL`, `AWS_SHARED_CREDENTIALS_FILE`, `AWS_CONFIG_FILE`, `AWS_CA_BUNDLE`, `AWS_EC2_METADATA_DISABLED`, `AWS_DISABLE_HOST_PREFIX_INJECTION` and **`AWS_REGION=us-west-2`**, and unset real credentials. `us-west-2` because regions are isolated in the emulator and AWS verifies fidelity in that region.
    - Telemetry stays at the emulator default (on). Drop homehub's telemetry-off flags.
    - `make down` stops and removes every project container: `funding.managed=true` ones (the forwarder and any emulator, Postgres, WireMock or OIDC container we started) and any named `funding-*`. A reused container from another project (same image, other name) is left running and listed.
  - **TLS:** the emulator's per-run `ca.pem` is trusted **only by the AWS SDK HTTP client** (`PemTrustManagers.fromPem` on `UrlConnectionHttpClient`). There is no JVM-wide trust store and no `JAVA_TOOL_OPTIONS`, so Temporal, Postgres and Maven are unaffected.
  - **`StartupGuard`:** the `local` profile requires a loopback `AWS_ENDPOINT_URL` and refuses to fall back to real AWS. The `aws` profile forbids bootstrapping.
  - **`InfraBootstrapRunner`** (`local` profile): the emulator keeps state in memory, so this runs on every start and is idempotent. It creates the `funding-pending-review-<FUNDING_ENV>` queue and its DLQ, with `RedrivePolicy maxReceiveCount=5` and visibility timeout 60 s. It doesn't create a Lambda, role or mapping locally (see the verification results).
  - **`EmulatorCalls`:** wraps bootstrap calls. HTTP 501 `NotImplemented` or 503 fails fast and names the service and operation (homehub V-17).
  - The SQS client sets `SdkAdvancedClientOption.DISABLE_HOST_PREFIX_INJECTION=true`.
  - Locally the handler runs on the host, so `FUNDING_API_BASE_URL` defaults to `http://localhost:8080` under `local`.
  - **Verification results.** Run 2026-11-17 against `aws-emulator:latest` (image built 2026-11-11), region `us-west-2`, AWS CLI 2.40.0.

    | ID | Verify | Result |
    |---|---|---|
    | V-1 | SQS standard queue, `RedrivePolicy` to DLQ, `ApproximateReceiveCount`, visibility timeout | ✅ **Pass.** Receive count went 1→2, then the message moved to the DLQ at `maxReceiveCount=2`. `PurgeQueue` is limited to once per 60 s, as on AWS. Queue URLs come back as `https://sqs.us-west-2.amazonaws.com/000000000000/<name>`; the SDK still sends requests to the endpoint override. |
    | V-2 | Lambda `java21`: create **and execute** | ❌ **Fail.** `CreateFunction` succeeds and the function goes `Pending`→`Active`, but `Invoke` returns `InvalidRuntimeException`: *"The runtime java21 is not supported by this emulator, which runs the Python and node runtimes."* `python3.13` executes. |
    | V-3 | SQS→Lambda event source mapping delivers batches and honors `ReportBatchItemFailures` | ✅ **Pass** (tested with `python3.13`). OK items were deleted. Items listed in `batchItemFailures` were retried, then went to the DLQ. `CreateEventSourceMapping` enforces AWS rules: the queue visibility timeout must be ≥ the function timeout, and the execution role needs `sqs:ReceiveMessage`, `DeleteMessage`, `GetQueueAttributes` and `ChangeMessageVisibility`. |
    | V-4 | A Lambda in the emulator can reach the host app | ❌ **Fail.** Lambda outbound HTTP is sandboxed to emulated services. `host.docker.internal` and the raw host-gateway IP `192.168.65.254` both fail with *"addresses no service this emulator serves: sqs, events, eventbridge, dynamodb, logs, cloudwatch, apigateway, secretsmanager, …, stepfunctions, s3, rds-data, appconfig"*. |
    | V-5 | Reuse a running container that publishes no port and has no mount (`practical_merkle`, started with a plain `docker run`) | ✅ **Reusable.** `docker cp` of the creds and CA plus an `alpine/socat` forwarder on `127.0.0.1:9297` gave health `ok` and `sts get-caller-identity` → `000000000000`. The container's bridge IP (`172.17.0.2`) is not reachable from macOS directly. |

  - **What this means:** no Lambda running in the emulator can call our Spring API, in any language (V-4). The trigger Lambda stays **Java 21** for real AWS, and locally it runs on the host:
    - **`LocalLambdaRunner`** (`local` profile, **always on**) long-polls `funding-pending-review` in the emulator, builds an `SQSEvent` (batch of up to 10), and calls the **same** `SqsTriggerHandler`.
    - It mimics the event source mapping: it deletes messages not listed in `batchItemFailures`, and leaves the listed ones for visibility-timeout redelivery and DLQ redrive. SQS and the DLQ are real emulator behavior (V-1). Only the poller is local.
    - `InfraBootstrapRunner` creates only the queues and DLQ, with visibility timeout 60 s > Lambda timeout 30 s. **Constraints for the future IaC** (out of scope here; found by V-3): visibility timeout ≥ function timeout; the execution role needs `sqs:ReceiveMessage`, `DeleteMessage`, `GetQueueAttributes` and `ChangeMessageVisibility`; and the mapping needs `ReportBatchItemFailures`.
    - Re-check V-2 and V-4 on each emulator release. If both pass, switch `local` to deploying the real function plus the mapping and drop `LocalLambdaRunner`.
- **Database: PostgreSQL 18 in Docker** (`postgres:18`). This is the owner's decision (2026-11-17), made after the emulator RDS probe below.
  - `scripts/db-env.sh` follows the same rule as the emulator: **reuse** a running `postgres:18` container that publishes a port (`DB_CONTAINER` override). Only if none is reachable, start `funding-postgres` (label `funding.managed=true`, `127.0.0.1:55432:5432`, named volume `funding-pgdata`, so data survives restarts).
  - It then exports `FUNDING_DB_URL`, `FUNDING_DB_USER` and `FUNDING_DB_PASSWORD`. Port 55432 avoids clashing with Homebrew Postgres on 5432.
  - Tests use Testcontainers `postgres:18`, the same image.
  - **Emulator RDS probe (2026-11-17, engine 18.3).** `CreateDBInstance` works, but the endpoint binds `127.0.0.1:<port>` *inside* the emulator container (reaching it needs a relay in the container's network namespace). The engine is **SQLite behind the Postgres wire protocol**:

    | Probe | Result |
    |---|---|
    | `SERIAL` on insert | Returns NULL, so FK inserts fail |
    | `NUMERIC` | Floating point: `0.1+0.2 = 0.30000000000000004` |
    | `now()`, `interval`, `jsonb_build_object` | Syntax error |
    | PL/pgSQL functions, triggers, `DO` | Syntax error |
    | `pg_try_advisory_lock`, `information_schema` | Fail, so Flyway can't run |
    | Unique / partial unique / CHECK / FK constraints, `ON CONFLICT`, `pg_namespace` | ✅ Work |

    Re-probe on each emulator release. The app reaches the database only through `FUNDING_DB_URL`, so switching needs no code change.
- **Isolation on shared services (NFR-08).** One environment name, `FUNDING_ENV`, keeps the emulator, Temporal and Postgres shared safely:

  | Resource | Default (`local`) | Functional tests |
  |---|---|---|
  | SQS queue / DLQ | `funding-pending-review-local` (+ `-dlq`) | `funding-pending-review-it-<runId>` (deleted after the run) |
  | Temporal workflow IDs | `funding-local-<versionId>` | `funding-it-<runId>-<versionId>` |
  | Temporal task queues | `funding-orchestration-local`, … | `…-it-<runId>` (the test JVM runs its own workers) |
  | Postgres | database `funding` | database `funding_it_<runId>` on the same server (Flyway + seed, dropped after the run) |

- **WireMock (external API mocks).** `scripts/wiremock-env.sh` follows the same reuse-first rule:
  1. Use the single running `wiremock/wiremock` container that publishes a port, or `WIREMOCK_CONTAINER` if set.
  2. Otherwise start `funding-wiremock` (label `funding.managed=true`, `127.0.0.1:8089:8080`, `--disable-banner`; the request journal stays on so the unmatched-request check works).
  3. Then export `FUNDING_INCOME_BASE_URL` and `FUNDING_DISBURSEMENT_BASE_URL`.
  - Stub isolation on a shared WireMock: every generated stub carries metadata `{env: <FUNDING_ENV>}`, and request keys include the env (`enc:<env>:<scenario>`). Loading replaces only this env's stubs (`/__admin/mappings/remove-by-metadata`), never another project's.
  - At startup, under `local`, `MockStubLoader` generates the stubs, runs the contract gate, and imports them (`/__admin/mappings/import`).
- Every endpoint is set by an environment variable. Nothing is hard-coded.

## Configuration (`funding.*`, FR-12)

| Property | Default | Spec |
|---|---|---|
| `funding.env` (`FUNDING_ENV`) | `local` | NFR-08 |
| `funding.tila.sum-tolerance` / `payment-tolerance` | `0.00` / `0.01` | C-2 |
| `funding.price.tolerance` | `0.00` | AS-3.1 |
| `funding.income.variance-threshold` | `0.15` | AS-5.1 |
| `funding.confidence.flag-below` | `0.90`. Driven by config (B4); `ConfidencePolicy` computes `is_flagged`, and no DB column holds it. | US-7 |
| `funding.gap.primary-lender-name` | `Summit Auto Finance` | AS-6.2 |
| `funding.workflow.expire-after` | `P30D` (from `submitted_at`) | AS-8.6, C-11 |
| `funding.activity.retry.*` | 1 s initial, ×2, 1 min max, 5 attempts | NFR-03 |
| `funding.activity.start-to-close` | DB 10 s, external API 15 s | NFR-01 |
| `funding.trigger.api-key` (`FUNDING_TRIGGER_API_KEY`) | (required) | AS-1.7 |
| `funding.trigger.local-runner.enabled` | `true` under `local` | V-2, V-4 |
| `funding.temporal.target` / `namespace` | `localhost:7233` / `default` | — |
| `funding.db.*` (`FUNDING_DB_URL`, …) | from `db-env.sh` | — |
| `funding.seed-data` / `funding.seed.volume` | `true` / `false` under `local`; forbidden under `aws` | seed-data.md |
| `funding.income.base-url` / `funding.disbursement.base-url` | from `wiremock-env.sh` locally | FR-11 |
| `funding.mocks.min-per-band` | `5` | P-5.6 |

## Keeping Seed Data in Sync with the Rules (constitution P-2)

- **`funding-domain`** exposes the pieces both the app and the generator use: `RuleCatalog` (enum of rule ids, each linked to its evaluator), `RuleEngine.evaluate(Snapshot, RulesConfig) → Map<Check, CheckOutcome>` (pure), and the calculators (`Amortization`, `IncomeVariance`, `VinFormat`). `RulesConfig` binds the same `funding.*` properties the workflows use.
- **`funding-seed`** (depends on `funding-domain` only):
  - `ScenarioIntent` is parsed from YAML (Jackson YAML).
  - `SeedGenerator.generate(intent, baseline, RulesConfig) → Fixture`.
  - `SeedGates` runs conformance, coverage, lock and docs.
  - `SeedRegenerateMain` backs `make seed-regenerate`.
- **Rule fingerprints:**
  - At build time, `maven-antrun` (or a small plugin) writes `META-INF/rule-fingerprints.properties`: the SHA-256 of each evaluator's source file, keyed by `RuleCatalog` id.
  - At runtime, the lock gate combines that hash with the effective config values the rule reads, and compares the result to `test/data/rules.lock`.
  - A refactor that doesn't change behavior still makes the lock stale. That's intended: `make seed-regenerate` re-verifies the scenarios and the lock diff shows it in review.
- **Startup:** `SeedDataLoader` (`funding-api`, `local` only) calls `SeedGates.verifyAll()` before inserting. On failure it fails startup with the scenario id, the rule, and the expected and actual outcomes, plus the hint `run make seed-regenerate after updating the scenario intent`.
- **Each seeded application stores its scenario fingerprint** (`seed_scenarios` table, local only), so re-seeding replaces stale rows instead of skipping them.
- **Mock stubs (P-5):**
  - `MockStubGenerator` builds WireMock mappings from two sources: (a) scenario `externals:` intents, one stub per scenario per API, and (b) each `test/data/mocks/<api>.catalog.yaml`, expanded as bands × magnitudes × shapes × fault classes.
  - Values, delays and retry counts are resolved from `RulesConfig` (variance threshold, `retry.max-attempts`, `start-to-close`).
  - Each catalog entry's band supplies an **independent expected outcome**. The conformance gate compares it with `RuleEngine` on the parsed response, and the matrix ITs compare it with the full HTTP + activity + retry path.
  - `RuleCatalog` declares `apis` per rule. A changed rule fingerprint marks all of that API's stubs for re-verification.
  - `MockLockGate` diffs against `test/data/mocks.lock`. Expected-outcome flips fail unless `ACCEPT_OUTCOME_CHANGES=<RULE_ID>` is set and `RULE-CHANGES.md` has a matching entry.
  - Each run writes `target/seed-report/mock-impact.md`.
  - `MockControl` (test helper) can only swap in generated intents.
- **Agent rule:** the repo `CLAUDE.md` repeats P-2 and P-5. Any change under `funding-domain/**/rules/**` or to `funding.*` rule config must be followed by `make seed-check` in the same change.

## Funder & Dev API (summary; full contract is the OpenAPI doc)

| Method & path | Purpose | Spec |
|---|---|---|
| `GET /api/v1/applications/{id}` | Overview (latest version) | AS-10.1 |
| `GET /api/v1/applications/{id}/versions` | Version history | US-9 |
| `GET /api/v1/versions/{vid}/stipulations` · `POST …` (manual) · `PATCH …/{stipId}` | Stips | AS-10.2, AS-10.8 |
| `PUT /api/v1/versions/{vid}/assignee` | Reassign the funder | AS-10.3 |
| `GET /api/v1/versions/{vid}/validation-results?history=true` | Results with expected vs actual | AS-10.4 |
| `GET /api/v1/versions/{vid}/documents` · `…/documents/{docId}/fields` | Document versions, fields, confidence, boxes | AS-10.5 |
| `POST /api/v1/versions/{vid}/approve` · `/reject` · `/rerun` · `/retry-disbursement` | Workflow actions | AS-10.6, AS-8.7 |
| `GET /api/v1/versions/{vid}/audit` | Audit trail | FR-10 |
| `POST /api/v1/funding-workflows` · `GET …/{vid}` | Trigger API (`X-Trigger-Api-Key`) | FR-03 |
| `POST /api/v1/external/verify-income` · `POST /api/v1/external/disbursements` | External APIs, served by WireMock locally | FR-11 |
| `POST /api/v1/dev/applications/{id}/submit` · `/versions` · `/documents` | Dev only (`local`) | FR-16 |

## Testing Strategy (SDD: each acceptance scenario has a test)

| Layer | What | Tooling |
|---|---|---|
| Rule unit tests | Each US-2 to US-7 scenario, plus edge cases (0% APR, rounding, lowercase VIN, I/O/Q in a VIN, boundary at 15.00%) | JUnit, parameterized |
| Workflow tests | Fan-out, partial failure, child ERROR, signals, supersede/`SUPERSEDED`, re-run attempts, `documentsUpdated`, expiry at `submitted_at` + P30D in every waiting state (PENDING_REVIEW/ERROR, STIPULATED, APPROVED), not reset by signals, in-flight disbursement completes first (time-skipping) | `TestWorkflowEnvironment`, mocked activities |
| Replay tests | Recorded histories still replay after code changes (NFR-07) | `WorkflowReplayer` |
| Seed gates (constitution P-2) | **Conformance:** every generated scenario produces its `expect` in the real rule engine. **Coverage:** every `RuleCatalog` rule has its required sides. **Lock:** `rules.lock` fingerprints are current. **Docs:** the generated table in `seed-data.md` is current. Runs in `mvn test`, so `make test-unit` fails on stale data. | JUnit in `funding-seed` |
| Persistence tests | Migrations, outbox trigger, partial unique indexes, idempotent stips, seed loader idempotency | Testcontainers Postgres 18 |
| Mock gates (P-5) | Contract (every stub validates against its OpenAPI, except entries marked `violatesContract`), catalog coverage (every band and fault class, `minPerBand` ≥ 5, every magnitude), band-oracle conformance, `mocks.lock` flip guard | JUnit in `funding-seed` (in `mvn test`) |
| API matrix ITs | One case per catalog entry (about 150 income, about 50 disbursement) through the real HTTP client, Temporal retry policy and rule; assert unmatched requests = 0 | In-JVM WireMock + `TestWorkflowEnvironment` |
| Volume functional | Submit about 200 `LA-5xxx`/`LA-6xxx` apps end to end | `make test-functional-volume` |
| Lambda unit tests | Response-code to ack/retry mapping, partial batch failures, malformed bodies, concurrent sends, remaining-time cutoff | JUnit + WireMock (start-workflow API) |
| Start-API tests | 202/200/409/400/401, supersede of the previous version, idempotent start | Spring `MockMvc` + Temporal test env |
| Trigger tests | Outbox to SQS (emulator) to `LocalLambdaRunner` + handler to API to workflow start; duplicate delivery; DLQ redrive | `make test-functional`: Failsafe ITs against the emulator from `emulator-env.sh` |
| E2E | Submit each `seed-data.md` scenario, assert its expected status and stips; run the funder-action scenarios | Real local Temporal + AWS Emulator + Postgres 18, `make test-functional` |

Test naming convention: `AS_2_2_sumMismatchCreatesTilaStip()`, so each test traces back to the spec.

## Owner Decisions

| # | Decision | Date |
|---|---|---|
| A1 | External APIs are only Income Verification and Disbursement for now (C-14). | 2026-11-17 |
| A2 | Build in **thin end-to-end slices** (see *Delivery Slices*), not layer by layer. | 2026-11-17 |
| A3 | Git repository **`loan-automation`** (GitHub remote `origin`, branch `main`), created by the owner on 2026-11-17. `rules.lock` / `mocks.lock` diffs are now reviewable in commits, alongside `mock-impact.md` and `RULE-CHANGES.md`. | 2026-11-17 |
| A4 | No CI for now. The gates run only locally (`make test-unit`, `make seed-check`). | 2026-11-17 |
| B1 | Spring Boot **3.5.x**. | 2026-11-17 |
| B2 | Java **21** (the Lambda runtime is `java21`). | 2026-11-17 |
| B3 | Base package and groupId **`com.funding`**. | 2026-11-17 |
| B4 | Confidence cut-off **driven by config**, `funding.confidence.flag-below = 0.90`. The source's generated `is_flagged` column is removed, and P-2 boundary scenarios follow the config. | 2026-11-17 |
| B5 | **The RISC must be complete before approval** (AS-8.10). RISC-field stips can't be waived. Documents **freeze** at APPROVED, FUNDED or REJECTED (AS-9.7, DB trigger), so a later change needs a new application version. | 2026-11-17 |
| B6 | Claude Code **hook runs `make seed-check`** after edits to rule, scenario, catalog, contract or `funding.*` config files. It's in `.claude/settings.json` (PostToolUse) and runs `scripts/hooks/seed-check-hook.sh`. It does nothing until the `seed-check` target exists; when the gates fail, it exits 2 so Claude has to fix the data. | 2026-11-17 |
| C-2…C-13 | All confirmed as proposed, **except C-11**: the application **expires after 30 days** (`EXPIRED`, terminal, clock from first submission). C-5: manual `STIP_PROOF_OF_INCOME` is created in the UI (spec 002). | 2026-11-17 |

## Delivery Slices (order for `tasks.md`)

| Slice | Delivers | Proves | Acceptance |
|---|---|---|---|
| **S1 — Walking skeleton** | Maven skeleton; Flyway baseline; `db-env.sh`, `emulator-env.sh`, `wiremock-env.sh`; outbox trigger → relay → SQS (emulator) → `LocalLambdaRunner` + handler → start API → `FundingWorkflow` with the **TILA** child only; `applyOutcome`; disbursement through WireMock; P-2 seed gates (conformance, coverage, lock, docs) for TILA; P-5 contract + catalog gates for Disbursement | Riskiest integrations end to end: Temporal, emulator SQS, Lambda handler path, gates | LA-1001 → FUNDED, LA-1002 → STIPULATED; AS-1.1–1.7, AS-2.x, AS-8.1–8.2 |
| **S2 — Document rules** | PRICE, VIN and GAP children; config-driven confidence and parse gating; GAP registry; RISC-completeness gate (AS-8.10) and document freeze (AS-9.7) | Fan-out of independent children; pure rules | AS-3.x, 4.x, 6.x, 7.x; scenarios LA-1003–1011, 1018–1026, 1031, 1033, 1034 |
| **S3 — Income + mock catalog** | INCOME child via WireMock; income catalog (about 150 stubs); matrix ITs; `mocks.lock` flip guard | External API, retries/timeouts, P-5 in full | AS-5.x; LA-1012–1017; income matrix IT |
| **S4 — Funder operations** | Funder API, signals, manual stips, approve/reject, audit, re-runs, 30-day expiry (`EXPIRED`) | Human-in-the-loop flows | US-8 (rest), US-10; funder-action scenarios |
| **S5 — Versioning** | Resubmission, supersede, document-version re-run trigger | Concurrency and history | US-9, AS-1.8; LA-1028, 1029, 1032 |
| **S6 — Breadth & hardening** | Disbursement catalog matrix, volume seed and test, replay tests, observability | Scale and change-safety | NFR-01, 07, 08; `make test-functional-volume` |

Every slice ends green on `make test-unit`, `make seed-check` and `make test-functional`.

## Next Step

`tasks.md` is generated. Start with S1 (T101).
