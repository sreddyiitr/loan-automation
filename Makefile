# funding-automation. See specs/001-loan-funding-validation/plan.md (Commands) and tasks.md.
SHELL := /bin/bash
JAVA  := source scripts/java-env.sh
EMU   := source scripts/emulator-env.sh
DB    := source scripts/db-env.sh
WM    := source scripts/wiremock-env.sh
MVN   := mvn -q -B

.PHONY: run down seed-check seed-regenerate seed-submit-all test-unit test-functional test-functional-volume verify-emulator

run:                      ## emulator + postgres + wiremock + app (local profile: bootstrap, seed, stubs)
	./run-local.sh

# Stops and removes every project container: those our scripts started (label funding.managed=true)
# and any named funding-* (e.g. one you started by hand, like funding-oidc). Volumes are kept, so
# Postgres data survives. Containers of another project that the env scripts merely reuse (same
# image, other name) may be shared, so they're listed, not stopped. Temporal isn't a container.
PROJECT_IMAGES := aws-emulator postgres:18 wiremock/wiremock ghcr.io/navikt/mock-oauth2-server
down:                     ## stop and remove every project container (funding.managed label or funding-* name)
	@ids=$$( { docker ps -aq --filter label=funding.managed=true; \
	           docker ps -a --format '{{.ID}} {{.Names}}' | awk '$$2 ~ /^funding-/ {print $$1}'; } | sort -u); \
	if [ -n "$$ids" ]; then \
	  names=$$(docker inspect -f '{{.Name}}' $$ids | sed 's#^/##' | tr '\n' ' '); \
	  docker stop $$ids >/dev/null 2>&1; docker rm -f $$ids >/dev/null 2>&1; \
	  echo "stopped and removed: $$names"; \
	else echo "no project containers running"; fi; \
	docker ps --format '{{.Names}} {{.Image}}' | while read -r name image; do \
	  for p in $(PROJECT_IMAGES); do \
	    case "$$image" in "$$p"|"$$p":*) echo "left running (not ours, may be shared): $$name ($$image)";; esac; \
	  done; \
	done

seed-check:               ## P-2/P-5 gates: conformance, coverage, lock, docs, mock contract/coverage/lock (writes nothing)
	@$(JAVA) && $(MVN) -pl funding-seed -am process-classes -Dseed.skip=false -Dseed.mode=check

seed-regenerate:          ## regenerate fixtures, stubs; rewrite rules.lock, mocks.lock, seed-data.md table only if gates pass
	@$(JAVA) && $(MVN) -pl funding-seed -am process-classes -Dseed.skip=false -Dseed.mode=regenerate \
	  $${ACCEPT_OUTCOME_CHANGES:+-Dfunding.seed.accept-outcome-changes=$$ACCEPT_OUTCOME_CHANGES}

seed-submit-all:          ## submit every seeded scenario through the real trigger path (app must be running)
	@ids=$$(ls test/data/scenarios | grep -E '^LA-[0-9]+' | sed -E 's/^(LA-[0-9]+).*/\1/' | sort -u); \
	for id in $$ids; do \
	  code=$$(curl -s -o /dev/null -w '%{http_code}' -X POST localhost:$${SERVER_PORT:-8080}/api/v1/dev/applications/$$id/submit); \
	  echo "$$id -> $$code"; \
	done

test-unit:                ## unit tests + seed gates (Testcontainers Postgres; no emulator)
	@$(JAVA) && $(MVN) test

test-functional:          ## Failsafe ITs against the shared emulator, Postgres, WireMock and Temporal (NFR-08 isolation)
	@$(JAVA) && $(EMU) && $(DB) && $(WM) && $(MVN) verify

test-functional-volume:   ## T604: one application per mock catalog entry through the real path; NFR-01 p95 report in funding-api/target/volume-report.md
	@$(JAVA) && $(EMU) && $(DB) && $(WM) && $(MVN) install -DskipTests && $(MVN) -pl funding-api verify -Dtest=NoUnitTests -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=FundingVolumeIT

verify-emulator:          ## T608: re-run plan V-2, V-4 and the RDS probe against the current emulator; writes target/verify-emulator.md
	@./scripts/verify-emulator.sh
