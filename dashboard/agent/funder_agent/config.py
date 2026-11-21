"""Where the agent's neighbours are. Every value can be overridden by an environment variable."""
import os

PORT = int(os.environ.get("AGENT_PORT", "8091"))
FUNDER_API_URL = os.environ.get("FUNDER_API_URL", "http://localhost:8080/api/v1")   # spec 001's funder API
TEMPORAL_UI_URL = os.environ.get("TEMPORAL_UI_URL", "http://localhost:8233")
TEMPORAL_NAMESPACE = os.environ.get("TEMPORAL_NAMESPACE", "default")
