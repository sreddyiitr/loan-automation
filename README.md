# funding-automation

**In plain English:** when someone buys a car with a loan, the dealer sends the lender a stack
of paperwork: the loan contract, the bill of sale and, often, a gap-insurance form. Before the
lender pays the dealer, a person (a *funder*) checks that it all adds up. Is the loan math right?
Are the car's price and serial number (VIN) the same on every page? Is the buyer's stated income
believable? Is the insurance form valid? This project does those checks automatically. When
everything is in order it pays the dealer by itself. When something is wrong it tells the dealer
exactly what to fix, waits for corrected paperwork or a funder's decision, checks again, and
closes the deal out after 30 days if nothing happens.

**Technically:** a backend that validates auto loan funding packages. The documents are a
Retail Installment Sale Contract (RISC), a Buyer's Order and a GAP agreement, already read by
OCR. The checks run as **Temporal workflows**, each failure becomes a *stipulation* (stip) the
dealer must clear, and a clean deal is disbursed automatically.

On top of it sits a **funder dashboard** (spec 002) for the deals that need a person: sign in, open
a deal, take it, and ask a local AI model to explain where it stands. Its screens are built with
**A2UI**, and its assistant runs on **ADK** with **Gemma** through Ollama.

Everything runs on your laptop: **Temporal**, **PostgreSQL 18** in Docker, the **AWS Emulator**
for SQS, **WireMock** for the external APIs, and, optionally, **Ollama** for the dashboard's
assistant. You need no AWS account, no paid AI service, and pay nothing.

The project is **spec-driven**: read [`specs/constitution.md`](specs/constitution.md) first, then
[`specs/001-loan-funding-validation/`](specs/001-loan-funding-validation/) and
[`specs/002-funder-dashboard/`](specs/002-funder-dashboard/). What's built so far is in
[`learnings/implementation_summary.md`](learnings/implementation_summary.md), and the dashboard is summarized in
[`learnings/ui_summary.md`](learnings/ui_summary.md).

## See it in action

![The funder dashboard in 38 seconds: sign in, open a deal, see every check and why it passed or failed, open a passed check's evidence, ask the local AI assistant for a summary, and open a funded deal](learnings/demo.webp)

A deal that needs a funder, from sign-in to the assistant's summary (local Gemma, live), then a
funded deal. Re-record it with `make demo` (see [Try the funder dashboard](#try-the-funder-dashboard-spec-002-optional)).

## Architecture

![How funding-automation fits together today: the funder's browser (React + A2UI) talks A2A to a Python agent, which builds screens in code, runs Summarize through ADK on local Gemma, and calls the Java funding app's funder API with the user's token; the funding app uses Postgres, Temporal, the AWS Emulator's SQS and WireMock, all on one laptop](Architecture.svg)

Top to bottom: the **browser** draws A2UI screens and signs in with OIDC. The **agent** builds
every screen in code, and only "Summarize" calls the local model, through ADK. The **funding app**
(spec 001) is the only thing that writes, and runs each deal as a Temporal workflow. Everything
under it runs locally. A PNG copy for slides or posts: [`Architecture.png`](Architecture.png).
The SVG is the source; edit it, then re-render the PNG.

## What it does today

All six slices of spec 001 are done: **S1 (walking skeleton)**, **S2 (document rules)**, **S3
(income and mock catalog)**, **S4 (funder operations)**, **S5 (versioning)** and **S6 (breadth and
hardening)**. End to end:

> **Check a deal and fund it.** Submit an application version. Five checks run **in parallel**:
>
> | Check | Verifies | Stip when it fails |
> |---|---|---|
> | **TILA** | `amount_financed + finance_charge = total_of_payments`, and the monthly payment matches the amortization formula | `STIP_TILA_MATH_DISCREPANCY` |
> | **Price** | the RISC cash price equals the Buyer's Order price | `STIP_PRICE_MISMATCH` |
> | **VIN** | VIN, year, make and model agree on every document and the application, and the VIN is valid | `STIP_VIN_MISMATCH` |
> | **GAP** | the GAP form is registered for the state and effective on the contract date, and the creditor is the primary lender | `STIP_GAP_FORM_MISMATCH`, `STIP_LENDER_MISMATCH` |
> | **Income** | the stated income is within 15% of what the Income Verification API reports (an external call with retries, timeouts and `Retry-After`; the SSN never leaves the DB except to that API) | `STIP_INCOME_VARIANCE_EXCEEDED`, `STIP_PROOF_OF_INCOME` |
>
> A field the OCR read with low confidence, or that is missing or unreadable, puts **just the
> check that needs it** into review (`STIP_EXTRACTION_REVIEW_REQUIRED`). If everything passes and
> the RISC is complete, the system pays the dealer. Otherwise the version waits for a funder.
>
> **Then a funder takes over** through the funder API (an OIDC bearer token on every call): waive or
> satisfy stips, add a manual stip, approve, reject, re-run checks, retry a failed disbursement,
> reassign. Each action is a guarded, audited DB write, then a signal to the waiting workflow.
> Only the status changes in spec §6 are allowed. A version not funded or rejected **30 days**
> after it was first submitted expires.
>
> **Corrections come as new versions.** A new document under review (say, a corrected RISC)
> re-runs **every** check, and a check that now passes clears its own stip. A resubmitted
> application version supersedes the old one's run, which keeps its results and stips as
> history. Once a version is approved its documents are frozen.

Behind that, in more detail (the spec 001 trigger path):

```
 dev submit ──▶ Postgres 18 ──trigger──▶ outbox ──relay──▶ AWS Emulator SQS ──▶ LocalLambdaRunner
 (stands in      (Docker, :55432)                          (Docker, :9297)       + the real Lambda handler
  for OCR)            ▲                                                                │
                      │ results, stips, audit                                          │ POST /api/v1/funding-workflows
                      │                                                                ▼
                 Temporal workers  ◀──────────────────── Spring Boot app (:8080) ──▶ Temporal (:7233)
                 FundingWorkflow ─▶ TILA ‖ PRICE ‖ VIN ‖ GAP ‖ INCOME checks (child workflows, in parallel)
                      │                                       └──HTTP──▶ WireMock: Income Verification API
                      ├── APPROVED ──HTTP──▶ WireMock (Docker, :8089): Disbursement API, generated stubs
                      └── otherwise waits for funder signals ◀── funder API (/api/v1/versions/…) ─ until day 30 → EXPIRED
```

- On startup, under the `local` profile, the app:
  1. **creates the SQS queue and its DLQ** in the emulator;
  2. **checks the seed data against the rules**, then loads the GAP form registry and **35 test
     scenarios** (all `DRAFT`, so nothing runs yet);
  3. **loads 322 generated WireMock stubs** (Disbursement and Income Verification: 252 catalog
     entries covering every band and fault class, plus one per scenario per API);
  4. starts the local Lambda runner.
- `make seed-submit-all` submits them all. The happy path (LA-1001) ends **FUNDED**. Each
  "broken" scenario ends **STIPULATED** with exactly the stip it targets: LA-1002 TILA sum,
  LA-1006 price off by 1¢, LA-1007 VIN differs on the GAP, LA-1012 income over by 33%, LA-1018
  expired GAP form, LA-1023 low-confidence cash price, and so on. LA-1016 (income API down) is
  held in `PENDING_REVIEW`, and LA-1030 (disbursement API down) stays `APPROVED` for a funder
  retry.
- Test data and mock responses are **computed from the business rules**, never typed by hand. If
  someone changes a rule without updating the scenarios, the build fails (constitution P-2,
  P-5). See [step 11](#step-11-change-a-rule-and-watch-the-gates-stop-you).

**Hardened (S6).**
- Every mock catalog entry runs through the real client: 186 income and 66 disbursement entries.
- A volume run submits one application per catalog entry (252) at once. With healthy APIs, p95
  validation time is **12.2 s** against the 30 s target.
- A worker killed mid-run comes back and finishes every run the same way.
- Every recorded workflow history must keep replaying.
- Activity and relay log lines carry the loan, version and workflow IDs, and Temporal's metrics
  appear at `/actuator/metrics`.

**The funder dashboard (spec 002), so far** (slices D0, D1 and D1b):
- **Sign in** on the local OIDC provider's login page. The funder API knows who you are from your
  token, and records you as the actor of every change.
- **Open a deal** by its loan id: status, facts, days to expiry, payment, version history, and a
  link to its workflow in the Temporal UI.
- **Take this deal**: assign an unassigned deal to yourself, after a confirmation. If someone else
  took it first, you're told who.
- **Summarize**: a local AI model explains in two or three sentences why the deal isn't funded,
  or how it ended. It's read-only, sees only this deal, and its words are shown as plain text.
  With no model running, everything else still works.
- **One command** (`make dashboard`) starts the funding API, the agent and the web app, and
  submits the seeded scenarios.

Code builds every screen and makes every write; the AI only explains. Next: roles and
permissions (D2), then the worklist and the other tabs. See
[Try the funder dashboard](#try-the-funder-dashboard-spec-002-optional).

---

## How we know it works

**550 automated tests, all passing** (one skipped by design), plus eight data gates. The functional tests run against the
**real** AWS Emulator, Postgres 18, WireMock and Temporal. The app starts, seeds, and handles each
scenario through the full trigger path.

| Suite | Tool | Runs against | Tests | Result |
|---|---|---|---:|---|
| Domain rules (incl. income, API classifier, status machine; spec 002's work routing and permissions) | JUnit 5, AssertJ | nothing (pure Java) | 273 | ✅ all pass |
| Persistence (incl. guarded status writes, document-added trigger, new versions) | JUnit 5, Testcontainers | `postgres:18` in Docker | 38 | ✅ all pass |
| Seed and mock gates (incl. every seeded scenario's routing) | JUnit 5, swagger-request-validator | the repo's scenario, catalog and contract files | 39 | ✅ all pass |
| Trigger Lambda | JUnit 5, WireMock | in-JVM WireMock as the start API | 9 | ✅ all pass |
| Workflows (incl. income retries/timeouts/429, SSN never leaks, funder signals, 30-day expiry, continueAsNew, supersede, document re-runs, replay suite, log correlation) | Temporal `TestWorkflowEnvironment` (time-skipping), Testcontainers, WireMock | test Temporal, Postgres, generated stubs, recorded histories | 64 | ✅ 63 pass, 1 skipped (the history recorder, on request only) |
| Trigger plumbing | JUnit 5 | nothing | 3 | ✅ all pass |
| API (start API, funder API incl. spec 002 overview fields, take and unassign, bearer-token security, dev endpoints, OpenAPI contract) | Spring MockMvc, Spring Security Test, Temporal test env | test Temporal + Postgres | 55 | ✅ all pass |
| **Functional** (every scenario, every income and disbursement catalog entry, 13 funder actions, real OIDC tokens, 6 versioning cases, worker restart, observability) | JUnit 5 + Spring Boot on a real port | **AWS Emulator SQS, Postgres 18, WireMock, Temporal, mock OIDC provider** | 68 | ✅ all pass |
| **Volume** (`make test-functional-volume`: 252 applications at once) | JUnit 5 + Spring Boot on a real port | the same | 1 | ✅ p95 12.2 s with healthy APIs (NFR-01: < 30 s) |
| Seed gates | `make seed-check` | — | 8 gates | ✅ conformance · coverage · lock · docs · mock contract · mock coverage · mock band oracle · mock lock |

Spec 002 (the funder dashboard) adds **160 agent tests** (pytest) and **17 browser tests**
(Playwright), counted separately; its Java tests are in the 550. None of them needs an AI model:
they replay answers recorded from it. See
[Try the funder dashboard](#try-the-funder-dashboard-spec-002-optional).

<sub>Last full run: 2026-11-21 (unit and functional; volume last run 2026-11-20), macOS (Apple silicon), Java 21.0.13, Maven 3.9.10, Temporal CLI 1.10.1 (server 1.32.2), WireMock 3.13.2, Postgres 18.7, mock-oauth2-server 6.1.5, AWS CLI 2.40.0. Reproduce with `make test-unit`, `make seed-check` and `make test-functional`.</sub>

---

## Before you start

You need the tools below, **a running Temporal server**, and four Docker images. One image you
build yourself; the other three download on first use.

### 1. Tools to install

| You need | Check with | Install (macOS) | Why |
|---|---|---|---|
| Docker Desktop | `docker ps` | [docker.com](https://www.docker.com/products/docker-desktop/) | Runs the emulator, Postgres and WireMock, plus the Testcontainers tests. **It must be running.** Give it at least 4 GB of memory, because the emulator alone is capped at 2 GiB. |
| Java 21 | `java -version` | `brew install openjdk@21` | Builds and runs the app. Homebrew's keg-only install is found automatically (`scripts/java-env.sh`). |
| Maven 3.9+ | `mvn -v` | `brew install maven` | Builds the modules. The first build downloads the dependencies into `~/.m2`. |
| Temporal CLI | `temporal --version` | `brew install temporal` | Provides the local Temporal server (step 2). Tested with CLI 1.10.1 / server 1.32.2. |
| `curl`, `nc`, `python3` | `which curl nc python3` | preinstalled on macOS | `curl` for health checks, `nc` for the Temporal reachability check, `python3` in some example commands. |
| `jq` | `jq --version` | `brew install jq` | Used by the Claude Code seed-check hook (`scripts/hooks/seed-check-hook.sh`). Optional if you don't use Claude Code. |
| AWS CLI v2 | `aws --version` | `brew install awscli` | Optional, but recommended. `emulator-env.sh` uses it to prove it's talking to the emulator (account `000000000000`), and step 8 uses it to explore SQS. |
| `uv` with Python 3.12 | `uv --version` | `brew install uv && uv python install 3.12` | **Dashboard only.** Runs the agent and its tests. |
| Node 22 or newer | `node --version` | `brew install node@22` | **Dashboard only.** Runs the web app and the Playwright tests (tested with 24). |
| Ollama with `gemma4:e4b` | `ollama list` | `brew install ollama && ollama serve && ollama pull gemma4:e4b` | **Optional, dashboard only:** the model behind **Summarize** (9.5 GB). Without it, only the assistant is unavailable. |

> **You never need real AWS credentials.** The emulator creates its own keys on every start, and
> the scripts read them for you.

### 2. Services that must be running

| Service | Who starts it | Address | Notes |
|---|---|---|---|
| **Temporal** | **you**: `make temporal` (step 2) | gRPC `localhost:7233`, UI http://localhost:8233 | **Required** for `make run` and `make test-functional`; the scripts never start it for you. `make test-unit` doesn't need it, because it uses Temporal's in-memory test server. Another address goes in `TEMPORAL_ADDRESS=host:port`. |
| AWS Emulator (SQS) | the scripts, or you (step 3) | `https://127.0.0.1:9297` | The scripts reuse a running `aws-emulator:latest` container, otherwise they start `funding-emulator`. |
| PostgreSQL 18 | the scripts | `127.0.0.1:55432` | The scripts reuse a running `postgres:18` container that publishes a port, otherwise they start `funding-postgres` (volume `funding-pgdata`). |
| WireMock | the scripts | `http://127.0.0.1:8089` | The scripts reuse a running `wiremock/wiremock` container that publishes a port, otherwise they start `funding-wiremock`. |
| OIDC provider (`mock-oauth2-server`) | the scripts, or you | `http://127.0.0.1:8090/funding` | Issues the funder API's bearer tokens (step 6) and the dashboard's login page. It must run with `scripts/oidc-config.json` (passed as `JSON_CONFIG`). `oidc-env.sh` reuses a running `mock-oauth2-server` container that publishes a port, otherwise it starts `funding-oidc` with the config. It pulls the image only if it isn't local. It refuses a running one with **no** published port, or **without the config**, and prints the command to fix it. |

### 3. Docker images

| Image | Size | How you get it | Used for |
|---|---:|---|---|
| `aws-emulator:latest` | ~1.6 GB | **You build it once** (step 1); AWS publishes no prebuilt image | SQS queue + DLQ |
| `postgres:18` | ~700 MB | Downloaded automatically the first time `db-env.sh` or the tests need it | The app database, and the Testcontainers tests |
| `wiremock/wiremock:latest` | ~480 MB | Downloaded automatically by `wiremock-env.sh` | Mocks of the Disbursement and Income Verification APIs |
| `ghcr.io/navikt/mock-oauth2-server:6.1.5` | ~145 MB | Downloaded automatically by `oidc-env.sh`, only if it isn't local | The local OIDC provider: bearer tokens for the funder API |
| `alpine/socat:latest` | ~16 MB | Downloaded automatically, **only if** your emulator container publishes no port | Port forwarder `funding-emulator-proxy` |
| `testcontainers/ryuk` | ~30 MB | Downloaded automatically by Testcontainers during `make test-unit` | Removes test containers after the tests |

To download everything up front (useful on a slow or metered network):

```bash
docker pull postgres:18
docker pull wiremock/wiremock:latest
docker pull ghcr.io/navikt/mock-oauth2-server:6.1.5
docker pull alpine/socat:latest
# aws-emulator: build it, see step 1
```

### 4. Free ports

`8080` (app), `7233` and `8233` (Temporal), `9297` (emulator), `55432` (Postgres), `8089` (WireMock), `8090` (OIDC provider).
For the dashboard: `8091` (agent), `5173` (web app), `8092` and `5174` (the browser tests' own
agent and web app), and `11434` (Ollama).
Check with `lsof -nP -iTCP:8080 -sTCP:LISTEN`. Already used by a container the scripts can reuse?
That's fine. Already used by something else? See [Troubleshooting](#troubleshooting).

### 5. Pre-flight check

Paste this to confirm everything is in place before step 4:

```bash
docker ps >/dev/null && echo "docker: ok"
java -version 2>&1 | grep -q '"21' && echo "java 21: ok"
mvn -v >/dev/null && echo "maven: ok"
nc -z localhost 7233 2>/dev/null && echo "temporal: ok (running)" || echo "temporal: NOT running -> make temporal"
docker image inspect aws-emulator:latest >/dev/null 2>&1 && echo "aws-emulator image: ok" || echo "aws-emulator image: MISSING -> step 1"
```

---

## Run it, step by step

### Step 1: Build the AWS Emulator image (first time only)

This is the same image homehub uses, so if you already built `aws-emulator:latest`, skip this
step. In an **empty folder** (for example `../aws-emulator`), create a `Dockerfile`:

```dockerfile
FROM public.ecr.aws/amazonlinux/amazonlinux:2023-minimal
ADD https://downloads.aws.com/aws-emulator/packages/aws-emulator/LTS/latest/aws-emulator-aarch64.rpm /tmp/aws-emulator.rpm
RUN rpm -i /tmp/aws-emulator.rpm && rm /tmp/aws-emulator.rpm \
    && mkdir -p /home/emulator && chown 1000 /home/emulator
USER 1000
ENV HOME=/home/emulator
EXPOSE 9297
ENTRYPOINT ["aws-emulator", "-b", "0.0.0.0"]
```

> **Intel machines:** replace `aws-emulator-aarch64.rpm` with `aws-emulator-x86_64.rpm`
> (`uname -m` prints `x86_64`).

```bash
docker build -t aws-emulator .
```

### Step 2: Start Temporal (required, in its own terminal)

```bash
make temporal        # gRPC on localhost:7233, web UI on http://localhost:8233
```

Leave it running for as long as you use the app or run `make test-functional`. If it isn't
running, `make run` stops with `run-local: Temporal is not reachable at localhost:7233`.

- `make temporal` runs `temporal server start-dev --db-filename ~/.temporal/funding.db`, so
  workflow runs **survive a restart** (another file: `make temporal TEMPORAL_DB=/path/file.db`).
  Plain `temporal server start-dev` keeps them only in memory: stopping it forgets every run,
  while Postgres still holds the deals, so their workflow links in the dashboard 404 and their
  workflows can't be signalled. If that happens, reseed (see Troubleshooting).
- If a Temporal server is already running on `localhost:7233` (for example for another project),
  just use it. This app's workflow IDs and task queues are prefixed with `FUNDING_ENV`, so they
  don't collide with anything else.
- A server at a different address goes in `TEMPORAL_ADDRESS=host:port`.

### Step 3: Start the emulator (optional)

**Option A (simplest): skip this step.** `make run` starts `funding-emulator` if no emulator is
running.

**Option B: run your own,** or keep the one you already use for homehub:

```bash
mkdir -p ~/.aws/emulator
docker run --rm --memory=2g --memory-swap=2g -p 127.0.0.1:9297:9297 \
  -e HOME="$HOME" -v "$HOME/.aws/emulator:$HOME/.aws/emulator" \
  aws-emulator -p 9297
```

**Which emulator do our scripts use?** `scripts/emulator-env.sh` decides:

1. `EMULATOR_CONTAINER=<name>` if you set it.
2. Otherwise **the one** running `aws-emulator:latest` container, whatever its name or port. If
   several are running, it stops and asks you to choose.
3. If that container **publishes no port** (a plain `docker run aws-emulator`), it starts a tiny
   forwarder, `funding-emulator-proxy` (`alpine/socat`), on `127.0.0.1:9297`. Docker Desktop for
   Mac can't route to container IPs directly.
4. If the container **doesn't mount `~/.aws/emulator`**, the script copies `ca.pem`, `config` and
   `emulator_credentials` out of it. It copies them again every time you source it, because the
   emulator makes new keys and a new CA on each start.
5. It checks `/__admin/health` **and** `aws sts get-caller-identity` → `000000000000`.
6. Only if none of that works does it start its own emulator.

> **Telemetry:** this project leaves the emulator's usage telemetry at its **default (on)**. That
> differs from homehub's `--telemetry off`. If you want it off, start your own emulator with
> `--telemetry off` ([how][telemetry-off]); the scripts will reuse it.

### Step 4: Start the app

```bash
make run
```

`make run` sources the three env scripts, builds the modules, and runs the app under the `local`
profile. You're ready when the JSON log shows:

```
emulator-env: reusing <container> at https://127.0.0.1:9297 region=us-west-2
db-env: reusing funding-postgres, jdbc:postgresql://127.0.0.1:55432/funding (user funding)
wiremock-env: reusing funding-wiremock at http://127.0.0.1:8089
... "queues ready: https://sqs.us-west-2.amazonaws.com/000000000000/funding-pending-review-local (DLQ …-dlq)"
... "seed data: 35 inserted, 0 replaced, 0 unchanged"
... "loaded 322 generated stubs for env local into http://127.0.0.1:8089"
... "local Lambda runner polling (stands in for the SQS event source mapping)"
```

Leave this terminal running. Useful pages:

- Swagger UI: http://localhost:8080/swagger-ui.html
- Health: http://localhost:8080/actuator/health
- Metrics (Spring and Temporal SDK, `local` only): http://localhost:8080/actuator/metrics;
  the Temporal SDK's meters are the names starting with `temporal_`
- Temporal UI: http://localhost:8233 (look for `funding-local-<versionId>`)

### Step 5: Submit the scenarios and watch them settle

In another terminal:

```bash
make seed-submit-all              # LA-1001 -> 200, LA-1002 -> 200, ...
```

Each submit flips a version from `DRAFT` to `PENDING_REVIEW`. That runs the **whole real path**
(DB trigger → outbox → SQS → Lambda handler → start API → workflow). A second later:

```bash
source scripts/db-env.sh
docker exec "$DB_CONTAINER" psql -U "$FUNDING_DB_USER" -d funding -c "
  SELECT v.loan_application_id, v.version_status,
         (SELECT string_agg(stip_code, ',') FROM stipulations s WHERE s.application_version_id = v.application_version_id) AS stips,
         d.status AS disbursement, d.amount
    FROM application_versions v LEFT JOIN disbursements d USING (application_version_id) ORDER BY 1"
```

```
 LA-1001 | FUNDED     |                                             | COMPLETED | 28450.00
 LA-1002 | STIPULATED | STIP_TILA_MATH_DISCREPANCY                  |           |
 LA-1006 | STIPULATED | STIP_PRICE_MISMATCH                         |           |
 LA-1007 | STIPULATED | STIP_VIN_MISMATCH                           |           |
 LA-1012 | STIPULATED | STIP_INCOME_VARIANCE_EXCEEDED               |           |
 LA-1016 | PENDING_REVIEW |                                         |           |
 LA-1021 | STIPULATED | STIP_GAP_FORM_MISMATCH,STIP_LENDER_MISMATCH |           |
 LA-1022 | FUNDED     |                                             | COMPLETED | 28450.00
 LA-1023 | STIPULATED | STIP_EXTRACTION_REVIEW_REQUIRED             |           |
 ...
```

Ask the API what a workflow is doing (the API key is `local-dev-key` under `local`):

```bash
curl -s -H 'X-Trigger-Api-Key: local-dev-key' localhost:8080/api/v1/funding-workflows/2
# {"workflowId":"funding-local-2","runStatus":"RUNNING",
#  "state":{"step":"WAITING_FOR_FUNDER","status":"STIPULATED",
#           "outcomes":{"TILA":"FAIL","PRICE":"PASS","VIN":"PASS","GAP":"PASS","INCOME":"PASS"},
#           "attempts":{"TILA":1,"PRICE":1,"VIN":1,"GAP":1,"INCOME":1},"expiresAtEpochMillis":…}}
```

Want to submit one again? Submitting works only from `DRAFT` (409 `NOT_DRAFT` otherwise).
Restart the app after deleting its `seed_scenarios` row, or reset the volume with `make down`
plus `docker volume rm funding-pgdata`.

### Step 6: Act as a funder

Every funder API call, reads included, needs an OIDC **bearer token**. You get one from the local
provider in one call. The `client_id` you send becomes the user, and the app gives that user a role
from its user directory in `application.yml`: one specialist per kind of work, `gap-1`, `income-1`,
`risc-1`, `docs-1` (price and VIN) and `payment-1`, the catch-all `general-1`, and `supervisor-1`, who
can do everything (spec 002 D-15, D-17). Any other id gets 403 `UNKNOWN_USER`, and no
token gets 401. Errors are RFC 9457
`application/problem+json` with a `reason` (`VERSION_EXPIRED`, `PENDING_STIPS`,
`RISC_STIP_NOT_WAIVABLE`, …). The full contract is in
[`contracts/funder-api.openapi.yaml`](contracts/funder-api.openapi.yaml) and in the Swagger UI.

Clear LA-1006's price stip and watch it fund. A price mismatch is documents work, so act as `docs-1`,
and take the deal first: you can only work a deal that's assigned to you.

```bash
TOKEN=$(curl -s -X POST http://127.0.0.1:8090/funding/token \
  -d grant_type=client_credentials -d client_id=docs-1 -d client_secret=any -d scope=funding-api \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['access_token'])")
AUTH="Authorization: Bearer $TOKEN"                               # valid for an hour
VID=$(curl -s -H "$AUTH" localhost:8080/api/v1/applications/LA-1006 | python3 -c "import json,sys; print(json.load(sys.stdin)['applicationVersionId'])")
curl -s -X POST -H "$AUTH" localhost:8080/api/v1/versions/$VID/take    # assigned to docs-1 (409 ALREADY_ASSIGNED if someone has it)
curl -s -H "$AUTH" localhost:8080/api/v1/versions/$VID/stipulations   # note the stipId of STIP_PRICE_MISMATCH
curl -s -X PATCH localhost:8080/api/v1/versions/$VID/stipulations/<stipId> \
  -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"status":"WAIVED","reason":"1 cent, dealer confirmed"}'
# {"stipId":…,"status":"WAIVED","versionStatus":"APPROVED"}   … a second later the version is FUNDED
curl -s -H "$AUTH" localhost:8080/api/v1/versions/$VID/audit      # who did what (actor = docs-1), before and after
```

Other actions: `POST …/approve` (over an ERROR hold), `POST …/reject` with `{"reason":…}`,
`POST …/rerun` with `{"checks":["INCOME"]}`, `POST …/retry-disbursement` (LA-1030),
`POST …/stipulations` for a manual stip, `POST …/take` (assign to yourself, only if unassigned),
and `PUT …/assignee` (reassign, or unassign with `{"funderId":null}`). RISC-related stips can't be
waived or satisfied by hand; the dealer must send a corrected RISC (step 7).

### Step 7: Fix a document, or resubmit

Two more dev endpoints play OCR's part. **Add a document** to the latest version: under review
it re-runs every check; once approved it is refused with 409 `DOCUMENTS_FROZEN`. LA-1032 starts
like LA-1006 (Buyer's Order 1¢ off). Its intent's `amend:` block generates the corrected Buyer's
Order, so you don't type one. `make seed-regenerate` writes every generated fixture to
`target/generated-seed/<id>.json` (with nothing changed, it rewrites the same files):

```bash
make seed-regenerate
python3 -c "import json; print(json.dumps(json.load(open('target/generated-seed/LA-1032.json'))['amendments'][0]))" \
  | curl -s -X POST localhost:8080/api/v1/dev/applications/LA-1032/documents -H 'Content-Type: application/json' -d @-
# {"documentType":"BUYERS_ORDER","documentVersion":2,…}  … seconds later LA-1032 is FUNDED and its price stip SATISFIED by system:
```

(Submit LA-1032 first, step 5, and wait for STIPULATED: a document added to a DRAFT re-runs nothing.)

**Resubmit** as a new application version: `POST /api/v1/dev/applications/{id}/versions` copies
the latest version as a DRAFT (application fields and latest documents), with any documents in the
body (`{"documents":[…]}`) replacing those of the same type. Submit it and the old version's run
completes `SUPERSEDED`, its status and stips kept as history. LA-1028 is seeded that way: v1 is
STIPULATED history, and v2 funds when you submit it.

### Step 8: Look inside the emulator (SQS)

```bash
source scripts/emulator-env.sh
aws sts get-caller-identity --query Account        # "000000000000" (if not, STOP: that's real AWS)

aws sqs list-queues
aws sqs get-queue-attributes --attributes All \
  --queue-url https://sqs.us-west-2.amazonaws.com/000000000000/funding-pending-review-local \
  --query 'Attributes.{visibility:VisibilityTimeout,redrive:RedrivePolicy,waiting:ApproximateNumberOfMessages}'
```

The queue URLs say `sqs.us-west-2.amazonaws.com`, but the SDK still sends every request to
`AWS_ENDPOINT_URL`.

### Step 9: Look inside WireMock

```bash
curl -s localhost:8089/__admin/mappings | python3 -c "import json,sys; m=json.load(sys.stdin)['mappings']; print(len(m), 'stubs')"
curl -s localhost:8089/__admin/requests/unmatched        # must stay empty: a request no stub answers is a bug
```

Every stub carries `metadata.env`. The app replaces **only its own env's** stubs, so a WireMock
container shared with another project is safe.

### Step 10: Run the tests

```bash
make test-unit          # needs only Docker (Testcontainers postgres:18); includes the seed gates
make seed-check         # the 8 data gates, writes nothing
make test-functional    # needs Temporal + Docker; reuses your emulator, Postgres and WireMock
make test-functional-volume   # 252 applications (one per mock catalog entry) at once; ~2 min; NFR-01 report
make verify-emulator    # re-checks what the emulator can do (Lambda java21, Lambda -> host, RDS); see below
```

- `make test-functional-volume` seeds one application per mock catalog entry, submits them all
  through the real trigger path, and checks every status, stip and result. It writes
  `funding-api/target/volume-report.md`, with p50 and p95 validation times.
- `make verify-emulator` re-runs the checks that shaped the local setup: plan V-2, V-4 and the RDS
  probe. It creates temporary `funding-verify-*` resources in your emulator, removes them, and
  writes `target/verify-emulator.md` saying whether the local Lambda runner or `postgres:18` can
  now be replaced. Run it after an emulator upgrade.

`make test-functional` doesn't disturb your local data. Each run uses
`FUNDING_ENV=it-<runId>`: its own database `funding_it_<runId>`, its own queues, task queues,
workflow IDs and WireMock stubs. It removes all of them at the end.

### Step 11: Change a rule and watch the gates stop you

This step teaches the most about how the project works. Loosen the payment tolerance in
`funding-domain/src/main/resources/funding-rules.properties`:

```properties
funding.tila.payment-tolerance=0.02
```

```bash
make seed-check
#   FAIL lock
#        - TILA_PAYMENT: rule source or config changed (fingerprint 6f19a3ea4efb -> …). Run `make seed-regenerate` …
```

The rule changed, so every scenario that depends on it must be re-verified:

```bash
make seed-regenerate      # re-runs conformance + coverage, then rewrites test/data/rules.lock
git diff test/data/rules.lock
```

The lock diff shows a reviewer which rules changed and which scenarios were re-checked. If a
change makes a scenario's real outcome differ from its declared `expect`, `seed-regenerate`
refuses to write anything. The fix is to update the scenario intent, never to weaken the check.
Put the tolerance back when you're done (and run `make seed-regenerate` again).

### Try the funder dashboard (spec 002, optional)

You sign in on the OIDC provider's login page, open a deal by its loan id, and see its overview:
days to expiry, payment status, version history, and a link to the workflow in the Temporal UI.
An agent builds the screen in code (A2UI v1.0, no LLM) and React draws it. The agent calls the
funder API with your own token, and **Take this deal** writes through it. **Summarize** asks a
local model (Gemma through Ollama, $0, nothing leaves the laptop) to explain where the deal stands,
in a read-only panel. You need Python 3.12
with [`uv`](https://docs.astral.sh/uv/) (`brew install uv`) and Node 22 or newer, plus Temporal
(step 2).

```bash
make dashboard-preflight   # names each missing tool with its install command (make dashboard runs it too)
make dashboard             # the funding API, the agent (8091) and the web app (5173); prints the URL
make test-dashboard        # 160 agent tests, no services and no model needed
cd dashboard/web && npx playwright install chromium   # once
make test-dashboard-e2e    # 17 browser tests: their own agent and web app, make dashboard's API and sign-in
make record-assistant      # re-record the model's answers the tests replay (needs Ollama)
make demo                  # record the demo to target/demo: demo.mp4, demo.webp, demo.srt (needs ffmpeg)
```

`make dashboard` reuses each part that's already running (including an app from `make run`),
starts the rest, and submits the seeded scenarios still in DRAFT. The first start builds the app
and takes a few minutes; logs are in `target/dashboard/`. `Ctrl+C` stops only what it started.
Ollama is optional: without it the preflight warns, and only the assistant is unavailable.
With it (`brew install ollama && ollama serve && ollama pull gemma4:e4b`), Summarize answers in
about 1–2 s, or up to ~10 s when the model has to load first. The model is set in
`dashboard/agent/models.yaml`, not in code. The tests never need it: they replay answers recorded
from it.

Then, at http://localhost:5173:

![The funder dashboard: a deal's overview and the assistant's summary](learnings/screenshots/Funder%20dashboard.png)

1. Click **Sign in**. On the provider's page, type a user id, `docs-1` for example (or `gap-1`,
   `income-1`, `risc-1`, `payment-1`, `general-1`, `supervisor-1`), leave the claims box empty, and
   click **Sign-in**. There's no password.
2. Type a loan id, say `LA-1007`, and click **Open**.
3. **Take this deal** asks for confirmation, then assigns it to you. The browser tests leave
   LA-1006 assigned to `supervisor-1`, so as `docs-1` try Take on LA-1007 (a VIN mismatch, also
   documents work). Check the audit with
   `curl -s -H "$AUTH" localhost:8080/api/v1/versions/$VID/audit` (step 6).
4. Click **Summarize**. The panel says "Summarizing…", then shows the explanation. Good ones to
   try: `LA-1006` (price 1¢ off), `LA-1012` (income over by 33%), `LA-1016` (income API down),
   `LA-1030` (payment outcome unknown) and `LA-1001` (funded).

**The demo (`make demo`)** records the dashboard with Playwright: six scenes, each with an on-screen
caption and a visible pointer, then ffmpeg writes `target/demo/demo.mp4` (about 0.8 MB),
`demo.webp` (a silent loop, about 0.6 MB; the README's copy is `learnings/demo.webp`) and `demo.srt`.
For a voice-over, point `DEMO_VOICE_DIR` at a folder of `line-1.wav` … `line-6.wav`: each scene then
lasts as long as its line, and the voice is laid at each scene's start. The narration is in
`dashboard/web/demo/narration.json`. The MP4 and the voice clips stay local (`target/` is ignored);
only the WebP is committed.

The browser tests start their own agent (replaying recorded answers) and web app, so they always
test the current code; they need `make dashboard`'s funding API and sign-in. More, including how
the assistant works, in [`learnings/ui_summary.md`](learnings/ui_summary.md).

### Step 12: Stop everything

- Press `Ctrl+C` in the `make run` (or `make dashboard`) terminal and in the
  `make temporal` terminal. Runs stay in `~/.temporal/funding.db`; delete that file to forget them.
- `make down` stops and removes **every project container**: those the scripts started (label
  `funding.managed=true`, including the forwarder) and any container named `funding-*`, even one
  you started yourself (e.g. `funding-oidc`). The env scripts start them again on the next run;
  their images are already local, so nothing is downloaded.
- A container of **another project** that the scripts merely reused (same image, a different
  name) may be shared, so `make down` leaves it running and lists it.
- Postgres data survives in the `funding-pgdata` volume. Emulator data (queues, messages) lives
  only in memory and is recreated on the next `make run`.
- Ollama isn't ours: it keeps running until you quit it. It unloads the model after 5 idle
  minutes.

---

## What you'll learn

| Fact | What it means here |
|---|---|
| **The emulator can't run Java 21 Lambdas**, and its Lambdas can't reach your machine (plan V-2, V-4) | The trigger Lambda is real Java 21 code, and locally `LocalLambdaRunner` calls it. SQS, visibility timeouts and DLQ redrive are real emulator behaviour. |
| **The emulator's RDS is SQLite behind the Postgres protocol** (no triggers, floating-point `NUMERIC`) | The project uses real `postgres:18` in Docker instead. The app reaches it only through `FUNDING_DB_URL`. |
| **New credentials and a new CA on every emulator start** | Always `source scripts/emulator-env.sh` again after restarting it. The CA is trusted only by the AWS SDK client, never JVM-wide, so Temporal and Postgres connections are unaffected. |
| **A transactional outbox, not "send then commit"** | The status change and the outbox row commit together. A crash between the SQS send and the "published" mark only causes a duplicate, which the start API absorbs (`200 alreadyStarted`). |
| **Business FAIL is a value, not an exception** | Temporal retries only technical failures. A TILA mismatch is returned as an outcome and never retried. |
| **External calls are classified, not just retried** | The income client maps 5xx, timeouts and resets to retryable, honours a capped `Retry-After` on 429, and never retries a 422 or a malformed body. Exhausted retries become an `ERROR` hold, not a stip. |
| **Funder actions are "write, then signal"** | The API commits the guarded, audited change first and only then signals the workflow, which re-settles the status from the DB. A signal that arrives while the workflow is busy is never lost, and long-lived runs `continueAsNew` after 1000 signals. |
| **Workflow code changes need `Workflow.getVersion`** | Adding the INCOME child and the S4 signal loop would break replay of runs already in flight. Recorded histories from earlier slices are replayed in the tests to prove they still work. |
| **Every write re-checks "is this still the latest version?" in its own transaction** | A resubmission can't be overwritten by an older version's late result (AS-9.3). |
| **Test data is derived, not typed** | Scenarios say "offset the total by +10.00", and the generator computes 33802.60 with the same `Amortization` code the rule uses. |
| **Shared services need namespacing** | `FUNDING_ENV` scopes queue names, workflow IDs, task queues, the database and stub metadata, so tests and your local run can share one emulator, Postgres, WireMock and Temporal. |
| **Temporal's dev server forgets on restart** unless it has a file | `make temporal` keeps runs in `~/.temporal/funding.db`. Without it, a restart left the database pointing at runs that no longer existed. |
| **Code draws the screens, the LLM only explains** (spec 002) | The agent sends A2UI v1.0 JSON that it builds in code, and React renders it. The model's answer is checked in code and shown as plain text in a fixed panel, so it can never add a button. |
| **A2UI is strict, and that helps** | The official validator runs in the tests. It caught a real bug: sending `createSurface` twice for the same panel. The fix is to rebuild it (`deleteSurface` first). |
| **Small local models need small prompts** | Sending Gemma only what explains the deal (≈200 tokens, not ≈2,000) and turning thinking off (`reasoning_effort: none`) cut answers from ~4 s to ~1 s. ADK's `output_schema` asks Ollama to force JSON, which this model refuses, so the JSON is checked in code. |
| **Tests shouldn't need the model** | `make record-assistant` records the model's answers once, and the tests replay them. A test says when the recordings no longer match what the code sends. |

---

## Troubleshooting

| Message / symptom | Fix |
|---|---|
| `several emulator containers are running; choose one` | `EMULATOR_CONTAINER=<name> make run` |
| `… is running but not reachable (health, CA or credentials)` | Restart that emulator, or set `EMULATOR_CONTAINER` to another one |
| `image aws-emulator:latest not found` | Do step 1, or set `EMULATOR_IMAGE=<name:tag>` |
| `run-local: Temporal is not reachable at localhost:7233` | Start it: `make temporal` (step 2) |
| The dashboard's "Open the workflow in Temporal" shows 404, or decisions on old deals never complete | Temporal ran in memory and was restarted, so the runs are gone while Postgres still has the deals. Use `make temporal` from now on, and reseed once: stop `make dashboard`, then `source scripts/db-env.sh && docker exec "$DB_CONTAINER" psql -U "$FUNDING_DB_USER" -d postgres -c 'DROP DATABASE funding WITH (FORCE)' -c 'CREATE DATABASE funding'`, then `make dashboard` (it submits every seeded scenario) |
| `profile 'local' requires AWS_ENDPOINT_URL` | Start with `make run`, which sources the env scripts, not `mvn spring-boot:run` directly |
| `refusing to seed: seed gates failed` | Someone changed a rule or a scenario. Read the gate output, fix the intent, then run `make seed-regenerate` |
| `make seed-check` fails with `lock … changed` | Expected after a rule or config change: run `make seed-regenerate` and review the `rules.lock` diff |
| `401` from `/api/v1/funding-workflows` | Send `X-Trigger-Api-Key: local-dev-key` (or your `FUNDING_TRIGGER_API_KEY`) |
| `409 NOT_DRAFT` on submit | That version was already submitted; see the end of step 5 |
| `401 UNAUTHORIZED` from the funder API | Send `Authorization: Bearer <token>` (step 6). An expired token (after an hour), or one from another issuer or audience, is refused too. The issuer must be `127.0.0.1`, not `localhost` |
| `oidc-env: … runs without scripts/oidc-config.json` | Your provider container was started without the config, so browser-login tokens would have the wrong audience. Run the two commands it prints (the image is local) |
| The dashboard says your sign-in "has expired or isn't valid" right after signing in | The provider runs without `scripts/oidc-config.json` (source `scripts/oidc-env.sh` to check), or it restarted and the app still holds its old keys: restart the app |
| `403 UNKNOWN_USER` from the funder API | The token's user isn't in `funding.auth.users`; use `gap-1`, `income-1`, `risc-1`, `docs-1`, `payment-1`, `general-1` or `supervisor-1` |
| `409 VERSION_EXPIRED` / `VERSION_TERMINAL` / `NOT_LATEST_VERSION` | The version is closed (expired, funded or rejected) or was superseded; act on the latest version instead |
| `409 RISC_STIP_NOT_WAIVABLE` | TILA and RISC extraction stips can only be cleared by a corrected RISC |
| `409 DOCUMENTS_FROZEN` from the dev documents endpoint | The version is APPROVED, FUNDED, REJECTED or EXPIRED. Resubmit as a new version instead (step 7) |
| `409 VERSION_FUNDED` / `DISBURSEMENT_STARTED` when creating a version | The latest version is paid or its payment started. A second version would pay again, so it's refused. After an unknown outcome, retry the disbursement first |
| A new document doesn't re-run anything | The version was still DRAFT (submit it first), or it was no longer under review when the event arrived; the start API logs `NOT_RERUNNABLE` or `NO_OPEN_RUN` |
| A version stays `PENDING_REVIEW` | Check the Temporal UI for `funding-local-<id>`, and `curl localhost:8089/__admin/requests/unmatched` |
| `FUNDING_DB_URL is not set: run functional tests with make test-functional` | Run functional tests with `make test-functional`, not plain `mvn verify` |
| HTTP 403 from the emulator | Stale keys: `source scripts/emulator-env.sh` again |
| Port 8080 already in use | `lsof -ti tcp:8080 \| xargs kill`, or `SERVER_PORT=8081 make run` |
| Summarize says "the assistant is unavailable" | Ollama isn't running, or the model isn't pulled: `ollama serve`, then `ollama pull gemma4:e4b`. Everything else works without it |
| Summarize is slow the first time | Ollama loads the model after 5 idle minutes (up to ~10 s). Later answers take 1–2 s. The target is 15 s |
| `make test-dashboard-e2e` can't start: port 8092 or 5174 in use | A leftover test agent or web app: `lsof -ti tcp:8092 \| xargs kill` (or 5174) |
| `AS_10_3` fails, naming a deal | That deal no longer has its seeded status (for example LA-1006 waived by hand). Reseed as in the Temporal row above |
| An agent test says "rerun make record-assistant" | The prompt's shape changed since the answers were recorded. Run `make record-assistant` with `make dashboard` and Ollama running, and commit the new recordings |

## References

This project:

- [`learnings/implementation_summary.md`](learnings/implementation_summary.md): what's built, by module, and what's next.
- [`learnings/ui_summary.md`](learnings/ui_summary.md): the funder dashboard (spec 002, A2UI + ADK) at a glance:
  what's built and what's next, what's built by code and what by the LLM, roles, the local model,
  how to run it and its tests.
- [`specs/002-funder-dashboard/`](specs/002-funder-dashboard/): `spec.md`, `plan.md`, `tasks.md`
  (with notes on every task), and `a2ui-notes.md` (what we learned about A2UI, ADK and local Gemma).
- [`learnings/oidc_authn_authz_summary.md`](learnings/oidc_authn_authz_summary.md): sign-in and permissions explained
  simply: how the funder API knows who is calling (OIDC bearer tokens) and what they may do.
- [`learnings/a2ui_overview.md`](learnings/a2ui_overview.md): A2UI for newcomers, explained through the spike's code:
  surfaces, catalogs, the data model, bindings and the click round trip.
- [`learnings/workflows_summary.md`](learnings/workflows_summary.md): one loan followed through the Temporal UI, with
  screenshots of the timeline, each check's inputs and outputs, and what that gives an auditor.
- [`specs/constitution.md`](specs/constitution.md): the non-negotiable principles (spec first,
  rule-derived seed data and mocks, local-first, determinism).
- [`specs/001-loan-funding-validation/`](specs/001-loan-funding-validation/): `spec.md`
  (acceptance scenarios), `plan.md` (design and emulator verification results), `data-model.md`,
  `seed-data.md` (scenarios and mock catalog), `tasks.md` (ordered tasks and progress).
- `CLAUDE.md`: rules for AI-assisted changes (the seed-check hook enforces them).

AWS Emulator User Guide:

- [Getting started with Docker][docker]
- [Pointing your AWS SDK at AWS Emulator][sdk]
- [Telemetry in AWS Emulator][telemetry]

Others: [Temporal Java SDK](https://docs.temporal.io/develop/java) ·
[WireMock](https://wiremock.org/docs/) · [Testcontainers](https://java.testcontainers.org/) ·
[A2UI](https://a2ui.org) · [ADK](https://google.github.io/adk-docs/) · [Ollama](https://ollama.com)

[docker]: https://docs.aws.amazon.com/emulator/latest/userguide/getting-started-docker.html
[sdk]: https://docs.aws.amazon.com/emulator/latest/userguide/point-sdk-to-emulator.html
[telemetry]: https://docs.aws.amazon.com/emulator/latest/userguide/telemetry.html
[telemetry-off]: https://docs.aws.amazon.com/emulator/latest/userguide/telemetry.html#telemetry-opt-out
