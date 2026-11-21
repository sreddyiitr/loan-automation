"""The A2A server (plan §3). Our own AgentExecutor routes in code: a text request opens a screen, an
A2UI action runs its handler. Screens and writes never involve the LLM (FR-02, FR-03). The one
exception is ``deal.summarize``, which runs the assistant (ADK, D-14) and streams its panel as task
status updates: "Summarizing…" at once, then the answer (T154).
"""
from __future__ import annotations

import logging
import re
from typing import Any, Callable

import uvicorn
from a2a.server.agent_execution import AgentExecutor, RequestContext
from a2a.server.apps import A2AStarletteApplication
from a2a.server.events import EventQueue
from a2a.server.request_handlers import DefaultRequestHandler
from a2a.server.tasks import InMemoryTaskStore, TaskUpdater
from a2a.types import (AgentCapabilities, AgentCard, AgentSkill, HTTPAuthSecurityScheme, SecurityScheme,
                       TaskState, TextPart)
from a2a.utils import new_agent_parts_message, new_task
from a2ui.a2a import create_a2ui_parts, get_a2ui_agent_extension, get_a2ui_messages, try_activate_a2ui_extension
from starlette.middleware.cors import CORSMiddleware

from . import config
from .actions import deal
from .actions.assistant import STREAMED
from .assistant.service import SummaryService
from .api import ApiError, FunderApi, NotSignedIn, message_for
from .catalog import BASIC_CATALOG_ID, FUNDER_CATALOG_ID, VERSION
from .surfaces.notice import notice_surface

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("funder-agent")

LOAN_ID = re.compile(r"LA-[0-9A-Z-]+")
SIGN_IN = "Sign in to use the funder dashboard."


def bearer(context: RequestContext) -> str | None:
    """The browser's ``Authorization: Bearer`` token, relayed as is (FR-11)."""
    headers = (context.call_context.state.get("headers", {}) if context.call_context else {}) or {}
    value = headers.get("authorization") or headers.get("Authorization") or ""
    return value[7:].strip() if value.lower().startswith("bearer ") else None


def route(token: str | None, text: str, actions: list[dict[str, Any]],
          api_factory: Callable[[str | None], FunderApi] = FunderApi) -> list[dict[str, Any]]:
    """What to send back for this request. ``api_factory`` lets tests answer without a server."""
    try:
        with api_factory(token) as api:
            if actions:
                replies: list[dict[str, Any]] = []
                for action in actions:
                    name = action.get("name", "")
                    if name.startswith("ui."):
                        continue      # UI-only, e.g. the button that opens a confirmation (plan §3)
                    handler = deal.HANDLERS.get(name)
                    if handler is None:
                        log.warning("no handler for action %s", action.get("name"))
                        continue
                    replies += handler(api, action.get("context", {}))
                return replies
            loan = LOAN_ID.search(text)
            if not loan:
                return notice_surface("Open a deal by its loan id, for example LA-1006.")
            return deal.open_deal(api, loan.group(0))
    except NotSignedIn:
        return notice_surface(SIGN_IN)
    except ApiError as e:
        log.info("funder API refused: %s", e)
        return notice_surface(message_for(e.reason))


class FunderAgentExecutor(AgentExecutor):
    def __init__(self, card: AgentCard, api_factory: Callable[[str | None], FunderApi] = FunderApi,
                 assistant: SummaryService | None = None):
        self.card, self.api_factory, self._assistant = card, api_factory, assistant

    @property
    def assistant(self) -> SummaryService:
        if self._assistant is None:       # models.yaml is read on first use: screens never need it
            self._assistant = SummaryService.from_config()
        return self._assistant

    async def execute(self, context: RequestContext, event_queue: EventQueue) -> None:
        try_activate_a2ui_extension(context, self.card)
        incoming = get_a2ui_messages(context.message) if context.message else []
        actions = [m["action"] for m in incoming if "action" in m]
        streamed = next((a for a in actions if a.get("name") in STREAMED), None)
        if streamed:
            await self._stream(context, event_queue, streamed)
            return
        text = " ".join(p.root.text for p in (context.message.parts if context.message else [])
                        if isinstance(p.root, TextPart))
        messages = route(bearer(context), text, actions, self.api_factory)
        await event_queue.enqueue_event(new_agent_parts_message(create_a2ui_parts(messages, version=VERSION)))

    async def _stream(self, context: RequestContext, event_queue: EventQueue, action: dict[str, Any]) -> None:
        """Each step that isn't final goes out at once as a "working" status update (message/stream);
        the final one completes the task. Every step is a whole panel, so a plain message/send
        caller, which gets only the final status message, sees the answer too."""
        task = context.current_task
        if task is None:
            task = new_task(context.message)
            await event_queue.enqueue_event(task)
        updater = TaskUpdater(event_queue, task.id, task.context_id)
        parts = lambda messages: create_a2ui_parts(messages, version=VERSION)
        final: list[dict[str, Any]] = []
        try:
            with self.api_factory(bearer(context)) as api:
                async for messages, is_final in STREAMED[action["name"]](api, action.get("context", {}), self.assistant):
                    if is_final:
                        final = messages
                        break
                    await updater.update_status(TaskState.working, message=updater.new_agent_message(parts(messages)))
        except NotSignedIn:
            final = notice_surface(SIGN_IN)
        except ApiError as e:
            final = notice_surface(message_for(e.reason))
        await updater.complete(message=updater.new_agent_message(parts(final)))

    async def cancel(self, context: RequestContext, event_queue: EventQueue) -> None:
        raise NotImplementedError


def build_app(api_factory: Callable[[str | None], FunderApi] = FunderApi, assistant: SummaryService | None = None):
    """``api_factory`` and ``assistant`` let tests run the real A2A app with no funder API or model."""
    card = AgentCard(
        name="Funder dashboard agent", description="Builds the funder dashboard's A2UI surfaces.",
        url=f"http://localhost:{config.PORT}", version="0.1.0",
        default_input_modes=["text"], default_output_modes=["text"],
        capabilities=AgentCapabilities(streaming=True, extensions=[
            get_a2ui_agent_extension(version=VERSION, supported_catalog_ids=[BASIC_CATALOG_ID, FUNDER_CATALOG_ID])]),
        # FR-11: the caller sends the signed-in user's OIDC token; the agent relays it to the funder API.
        security_schemes={"bearer": SecurityScheme(root=HTTPAuthSecurityScheme(scheme="bearer", bearer_format="JWT"))},
        security=[{"bearer": []}],
        skills=[AgentSkill(id="deal", name="Deal view", description="Shows a deal's overview.", tags=["funder"]),
                AgentSkill(id="summary", name="Summarize", description="Explains where a deal stands (local model).",
                           tags=["funder", "assistant"])],
    )
    handler = DefaultRequestHandler(agent_executor=FunderAgentExecutor(card, api_factory, assistant),
                                    task_store=InMemoryTaskStore())
    app = A2AStarletteApplication(agent_card=card, http_handler=handler).build()
    app.add_middleware(CORSMiddleware, allow_origin_regex=r"http://(localhost|127\.0\.0\.1):\d+",
                       allow_methods=["*"], allow_headers=["*"])
    return app


def main() -> None:
    uvicorn.run(build_app(), host="localhost", port=config.PORT)
