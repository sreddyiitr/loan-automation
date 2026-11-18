#!/usr/bin/env bash
# Claude Code PostToolUse hook (owner decision B6, constitution P-2/P-5).
# After an edit to a rule, scenario, mock catalog, contract or funding.* config file,
# run `make seed-check`. Exit 2 (stderr goes back to Claude) if the gates fail.
# Silent no-op for unrelated files, or while the Makefile or seed-check target doesn't exist yet.
set -u
root="${CLAUDE_PROJECT_DIR:-$(cd "$(dirname "$0")/../.." && pwd)}"

file="$(jq -r '.tool_input.file_path // .tool_response.filePath // empty' 2>/dev/null)"
[ -n "$file" ] || exit 0
rel="${file#"$root"/}"

case "$rel" in
  funding-domain/*/rules/*|*/RuleCatalog*.java|test/data/scenarios/*|test/data/mocks/*|test/data/_baseline.yaml|test/data/gap-form-registry*|contracts/*) ;;
  funding-*/src/main/resources/application*.yml|funding-*/src/main/resources/application*.yaml) ;;
  *) exit 0 ;;
esac

[ -f "$root/Makefile" ] || exit 0
make -C "$root" -n seed-check >/dev/null 2>&1 || exit 0   # target not defined yet

if ! out="$(make -C "$root" -s seed-check 2>&1)"; then
  {
    echo "make seed-check FAILED after editing $rel (constitution P-2/P-5)."
    echo "Update the scenario/catalog intent, then run make seed-regenerate. Do not weaken the gates."
    echo "$out" | tail -n 60
  } >&2
  exit 2
fi
exit 0
