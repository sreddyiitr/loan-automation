"""Where the agent's neighbours are. Every value can be overridden by an environment variable."""
import os
from pathlib import Path

PORT = int(os.environ.get("AGENT_PORT", "8091"))
FUNDER_API_URL = os.environ.get("FUNDER_API_URL", "http://localhost:8080/api/v1")   # spec 001's funder API
TEMPORAL_UI_URL = os.environ.get("TEMPORAL_UI_URL", "http://localhost:8233")
TEMPORAL_NAMESPACE = os.environ.get("TEMPORAL_NAMESPACE", "default")
# The assistant's model registry (FR-07): which model each feature uses.
# Replay recorded answers instead of calling a model (AS-10.5): a directory, e.g. recordings.
ASSISTANT_REPLAY = os.environ.get("ASSISTANT_REPLAY", "")
ASSISTANT_MODELS = os.environ.get("ASSISTANT_MODELS", str(Path(__file__).resolve().parent.parent / "models.yaml"))
