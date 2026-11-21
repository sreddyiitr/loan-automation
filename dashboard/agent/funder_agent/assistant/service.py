"""The assistant as the server uses it: the configured model (FR-07), a quick "is it there?" check
(AS-8.4), and a fresh conversation per request (AS-8.5)."""
from __future__ import annotations

import logging
from typing import Any

import httpx

from .models import FeatureModel, Registry
from .summary import Answer, ConversationFactory, adk_conversations, summarize

log = logging.getLogger("funder-agent.assistant")


class SummaryService:
    def __init__(self, conversations: ConversationFactory, health: str | None = None, timeout: float = 1.5):
        self.conversations, self.health, self.timeout = conversations, health, timeout

    @classmethod
    def from_config(cls) -> "SummaryService":
        feature: FeatureModel = Registry.load().for_feature("summary")
        return cls(adk_conversations(feature), feature.spec.health)

    async def available(self) -> bool:
        """The model's ``health`` URL answers 2xx (models.yaml); no URL means "assume it's there"."""
        if not self.health:
            return True
        try:
            async with httpx.AsyncClient(timeout=self.timeout) as http:
                return (await http.get(self.health)).is_success
        except httpx.HTTPError as e:
            log.info("assistant unavailable: %s", e)
            return False

    async def summarize(self, data: dict[str, Any]) -> Answer:
        answer = await summarize(self.conversations(), data)
        log.info("summary: %s after %d attempt(s)%s", answer.kind, answer.attempts,
                 f" ({answer.error})" if answer.error else "")
        return answer
