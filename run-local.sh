#!/usr/bin/env bash
# Local run (plan "Local Environment"): reuse-first emulator, Postgres and WireMock, then the app
# under the 'local' profile (queue bootstrap, seed data, generated WireMock stubs, local Lambda runner).
# Temporal must already be running at ${TEMPORAL_ADDRESS:-localhost:7233}.
set -euo pipefail
cd "$(dirname "$0")"
source scripts/java-env.sh
source scripts/emulator-env.sh
source scripts/db-env.sh
source scripts/wiremock-env.sh
export FUNDING_ENV="${FUNDING_ENV:-local}"
temporal="${TEMPORAL_ADDRESS:-localhost:7233}"
nc -z "${temporal%:*}" "${temporal##*:}" 2>/dev/null \
  || { echo "run-local: Temporal is not reachable at $temporal" >&2; exit 1; }
mvn -q -B -DskipTests -pl funding-api -am install
exec mvn -q -B -pl funding-api spring-boot:run -Dspring-boot.run.profiles=local
