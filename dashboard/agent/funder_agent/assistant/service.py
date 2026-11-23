"""The assistant as the server uses it: the configured model (FR-07), a quick "is it there?" check
(AS-8.4), and a fresh conversation per request (AS-8.5)."""
from __future__ import annotations

import logging
from typing import Any, Callable

import httpx

from funder_agent import config

from .models import FeatureModel, Registry
from .summary import AdkConversation, Answer, ConversationFactory, adk_conversations, summarize

log = logging.getLogger("funder-agent.assistant")


class SummaryService:
    def __init__(self, conversations: ConversationFactory, health: str | None = None, timeout: float = 1.5):
        self.conversations, self.health, self.timeout = conversations, health, timeout

    @classmethod
    def from_config(cls) -> "SummaryService":
        """The configured model, or recorded answers when ASSISTANT_REPLAY names a directory (AS-10.5)."""
        if config.ASSISTANT_REPLAY:
            from .replay import ReplayService
            log.info("assistant: replaying recorded answers from %s (no model)", config.ASSISTANT_REPLAY)
            return ReplayService(config.ASSISTANT_REPLAY)
        feature: FeatureModel = Registry.load().for_feature("summary")
        log.info("assistant: summary on %s (thinking %s)", feature.spec.model, "on" if feature.thinking else "off")
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


class AssignService:
    """Assignment suggestions (US-12): the ``assign`` model from models.yaml, a health check, and a
    fresh ADK conversation per request with that request's read-only tools."""

    def __init__(self, conversations: Callable[[list], Any] | None, health: str | None = None, timeout: float = 1.5):
        self.conversations, self.health, self.timeout = conversations, health, timeout

    @classmethod
    def from_config(cls) -> "AssignService":
        if config.ASSISTANT_REPLAY:
            from .replay import ReplayConversation
            return cls(lambda tools: ReplayConversation(config.ASSISTANT_REPLAY, feature="assign", key="supervisor"))
        feature: FeatureModel = Registry.load().for_feature("assign")
        from .assign import INSTRUCTION
        from .summary import AdkConversation
        return cls(lambda tools: AdkConversation(feature.llm(), instruction=INSTRUCTION, name="assign", tools=tools),
                   feature.spec.health)

    async def available(self) -> bool:
        return await SummaryService(lambda: None, self.health, self.timeout).available()

    def conversation(self, tools: list) -> Any:
        return self.conversations(tools) if self.conversations else None


class DraftService(SummaryService):
    """Draft stip text (AS-8.3): the ``draft_stip`` model, or its recordings in replay mode."""

    @classmethod
    def from_config(cls) -> "DraftService":
        from .draft_stip import INSTRUCTION
        if config.ASSISTANT_REPLAY:
            from .replay import ReplayConversation
            return cls(lambda: ReplayConversation(config.ASSISTANT_REPLAY, feature="draft_stip"))
        feature: FeatureModel = Registry.load().for_feature("draft_stip")
        return cls(lambda: AdkConversation(feature.llm(), instruction=INSTRUCTION, name="draft_stip"), feature.spec.health)

    async def draft(self, data: dict[str, Any]) -> Answer:
        from .draft_stip import draft
        answer = await draft(self.conversations(), data)
        log.info("draft_stip: %s after %d attempt(s)%s", answer.kind, answer.attempts, f" ({answer.error})" if answer.error else "")
        return answer
