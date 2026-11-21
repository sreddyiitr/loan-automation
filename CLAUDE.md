# funding-automation

Auto loan funding validation: Java 21, Spring Boot, Temporal (`localhost:7233`), Postgres 18 (Docker), and AWS Emulator for SQS. Spec-driven development: read `specs/constitution.md` first, then `specs/001-loan-funding-validation/` (`spec.md`, `plan.md`, `data-model.md`, `seed-data.md`).

## Non-negotiable (from the constitution)

- **Spec first (P-1).** Every acceptance scenario `AS-x.y` has a test named `AS_x_y_…`. Behavior changes start in `spec.md`.
- **Seed data is derived from the rules (P-2).**
  - Whenever you change a business rule (anything under `funding-domain/**/rules/**`, `RuleCatalog`, or a `funding.*` threshold, tolerance, lender or registry value), update the affected `test/data/scenarios/*.yaml` intent **in the same change**, then run `make seed-regenerate`.
  - Never hand-edit generated numbers, `test/data/rules.lock`, or the generated table in `seed-data.md`.
  - Never weaken a conformance assertion to make it pass.
  - A new rule needs its `RuleCatalog` entry plus PASS and FAIL scenarios, and both boundary sides if it has a threshold.
- **Mock API responses follow the rules (P-5).**
  - External APIs are mocked only through **generated** WireMock stubs, built from scenario `externals:` and `test/data/mocks/<api>.catalog.yaml` and validated against `contracts/<api>.openapi.yaml`.
  - When a rule changes, inspect `target/seed-report/mock-impact.md` after `make seed-regenerate`.
  - If expected outcomes flip, add a `test/data/RULE-CHANGES.md` entry and rerun with `ACCEPT_OUTCOME_CHANGES=<RULE_ID>`. Never hand-edit stubs or `mocks.lock`.
  - A new external API needs a contract, a catalog covering every band and fault class (`minPerBand` ≥ 5), and an `apis` entry on its `RuleCatalog` rule.
- **Local services (P-3).** Reuse the developer's running emulator, Postgres, WireMock and OIDC (`mock-oauth2-server`) containers (`scripts/emulator-env.sh`, `db-env.sh`, `wiremock-env.sh`, `oidc-env.sh`), and start new ones only if none is reachable. Don't use LocalStack. Leave emulator telemetry at its default.

## Every PR updates the summaries

Before opening any PR, bring these up to date with what the PR changes (say "no change" in the PR body if one truly isn't affected):
- `ui_summary.md`: funder dashboard capabilities, what is fixed vs dynamic, components, model, setup.
- `implementation_summary.md`: what's built, tests and counts, decisions, known gaps.
- `README.md`: how to run and use it, test counts.

## Commands

`make run` · `make down` · `make seed-check` · `make seed-regenerate` · `make seed-submit-all` · `make test-unit` · `make test-functional` · `make test-functional-volume` · `make verify-emulator`

- `make test-unit` needs only Docker (Testcontainers `postgres:18`). `make test-functional` sources the env scripts and runs the `*IT` tests against the shared emulator, Postgres, WireMock and Temporal, isolated as `FUNDING_ENV=it-<runId>`.
- `scripts/*-env.sh` are sourced from both bash and zsh (macOS default), so keep them free of arrays and of reliance on word splitting.
- Progress: `specs/001-loan-funding-validation/tasks.md` (S1–S6 done). Summary: `implementation_summary.md`.
