"""Recorded model answers (spec 002 AS-10.5, T155): the tests need no running model.

``make record-assistant`` asks the configured model to summarize a few seeded deals and saves each
conversation to ``recordings/summary/<loanApplicationId>.json``. With ``ASSISTANT_REPLAY=<dir>``,
the agent answers from those files instead of calling a model. A deal with no recording behaves
as if no model is running ("assistant unavailable", AS-8.4), which is how the browser tests see it.
"""
from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any

from .service import SummaryService
from .summary import ModelUnavailable

LOAN_ID = re.compile(r'"loanApplicationId": "(LA-[0-9A-Z-]+)"')


def recording_path(directory: str | Path, loan_id: str, feature: str = "summary") -> Path:
    return Path(directory) / feature / f"{loan_id}.json"


def load_recording(directory: str | Path, loan_id: str, feature: str = "summary") -> dict[str, Any] | None:
    path = recording_path(directory, loan_id, feature)
    return json.loads(path.read_text()) if path.exists() else None


class ReplayConversation:
    """Plays one recording back. For the summary, the first message names the deal; for a feature
    recorded once (assignment suggestions), ``key`` names the file. Each send returns the next reply."""

    def __init__(self, directory: str | Path, feature: str = "summary", key: str | None = None):
        self.directory, self.replies, self.feature, self.key = directory, None, feature, key

    async def send(self, text: str) -> str:
        if self.replies is None:
            m = None if self.key else LOAN_ID.search(text)
            name = self.key or (m.group(1) if m else None)
            recording = load_recording(self.directory, name, self.feature) if name else None
            if recording is None:
                raise ModelUnavailable(f"no {self.feature} recording for {name or 'this deal'} (replay mode)")
            self.replies = list(recording["replies"])
        if not self.replies:
            raise ModelUnavailable("the recording has no more replies (replay mode)")
        return self.replies.pop(0)


class ReplayService(SummaryService):
    def __init__(self, directory: str | Path):
        super().__init__(lambda: ReplayConversation(directory), health=None)
        self.directory = directory
