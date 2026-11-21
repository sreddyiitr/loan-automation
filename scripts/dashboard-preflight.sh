#!/usr/bin/env bash
# Spec 002 AS-10.2: names each missing prerequisite for the funder dashboard, with its install
# command. Run it directly (make dashboard runs it first). Exit 1 if anything required is missing.
# Required: spec 001's (Java 21, Docker running, Temporal reachable), Python 3.12 with uv, Node 22+.
# Optional: Ollama with gemma4:e4b, only for the assistant (AS-10.4): a warning, never a failure.
# No arrays and no word splitting, so it behaves the same under bash and zsh (CLAUDE.md).
cd "$(dirname "$0")/.." || exit 1

missing=0
ok()   { printf '  ok      %s\n' "$1"; }
miss() { printf '  MISSING %s\n          install: %s\n' "$1" "$2"; missing=$((missing + 1)); }
warn() { printf '  warn    %s\n          %s\n' "$1" "$2"; }

echo "dashboard preflight"

# --- spec 001's prerequisites
if (source scripts/java-env.sh >/dev/null 2>&1 && "$JAVA_HOME/bin/java" -version 2>&1 | grep -q 'version "21'); then
  ok "Java 21"
else
  miss "Java 21" "brew install openjdk@21"
fi
if docker info >/dev/null 2>&1; then
  ok "Docker (running)"
else
  miss "Docker, running" "install Docker Desktop (https://www.docker.com/products/docker-desktop/) and start it"
fi
temporal="${TEMPORAL_ADDRESS:-localhost:7233}"
if nc -z "${temporal%:*}" "${temporal##*:}" >/dev/null 2>&1; then
  ok "Temporal at $temporal"
else
  miss "Temporal at $temporal (you start it; the scripts never do)" "brew install temporal, then in its own terminal: temporal server start-dev"
fi
if command -v python3 >/dev/null 2>&1; then
  ok "python3 (example commands, oidc-env.sh)"
else
  miss "python3" "xcode-select --install"
fi

# --- spec 002's
if command -v uv >/dev/null 2>&1; then
  ok "uv $(uv --version 2>/dev/null | awk '{print $2}')"
  if uv python find 3.12 >/dev/null 2>&1; then
    ok "Python 3.12 (through uv)"
  else
    miss "Python 3.12" "uv python install 3.12"
  fi
else
  miss "uv (and Python 3.12 through it)" "brew install uv && uv python install 3.12"
fi
if command -v node >/dev/null 2>&1; then
  major="$(node --version | sed 's/^v//; s/\..*//')"
  if [ "$major" -ge 22 ] 2>/dev/null; then
    ok "Node $(node --version)"
  else
    miss "Node 22 or newer (found $(node --version))" "brew install node@22"
  fi
else
  miss "Node 22 or newer" "brew install node@22"
fi

# --- optional: the assistant's local model (D-7, AS-10.4)
if ! command -v ollama >/dev/null 2>&1; then
  warn "Ollama not installed: the assistant will show as unavailable (everything else works)" \
       "to use it: brew install ollama && ollama serve && ollama pull gemma4:e4b"
elif ! curl -s -m 2 localhost:11434/api/tags >/dev/null 2>&1; then
  warn "Ollama installed but not running: the assistant will show as unavailable" "start it: ollama serve"
elif ! curl -s -m 2 localhost:11434/api/tags | grep -q '"gemma4:e4b"'; then
  warn "Ollama is running, but gemma4:e4b isn't pulled: the assistant will show as unavailable" "ollama pull gemma4:e4b"
else
  ok "Ollama with gemma4:e4b (optional, for the assistant)"
fi

if [ "$missing" -gt 0 ]; then
  echo "dashboard preflight: $missing missing; install them and run again."
  exit 1
fi
echo "dashboard preflight: all set."
