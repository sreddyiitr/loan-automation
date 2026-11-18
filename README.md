# funding-automation

Auto loan funding validation: Java 21, Spring Boot 3.5, Temporal, Postgres 18, AWS Emulator (SQS), WireMock.

Start with `specs/constitution.md`, then `specs/001-loan-funding-validation/` (`spec.md`, `plan.md`, `tasks.md`).

```sh
make test-unit          # unit tests + seed gates (needs Docker)
make seed-check         # P-2/P-5 gates: conformance, coverage, locks, docs, mock contract
make run                # emulator + postgres + wiremock (reused if running) + app, profile 'local'
make seed-submit-all    # submit every seeded scenario through the real trigger path
make test-functional    # end-to-end against the shared local services (needs Temporal at localhost:7233)
make down               # stop only containers these scripts started
```
