"""Spike D0 / T001: an A2A server whose executor answers with A2UI v1.0, routed in code (no LLM, no ADK).

- A text request ("open LA-1006") returns the deal overview surface.
- An A2UI `action` (e.g. the `deal.take` button) is handled by code and returns a data-model update.
This mirrors the official A2UI sample's own AgentExecutor; ADK will sit behind it only for the assistant.
"""
import logging
from typing import Any

import uvicorn
from a2a.server.agent_execution import AgentExecutor, RequestContext
from a2a.server.apps import A2AStarletteApplication
from a2a.server.events import EventQueue
from a2a.server.request_handlers import DefaultRequestHandler
from a2a.server.tasks import InMemoryTaskStore
from a2a.types import AgentCapabilities, AgentCard, AgentSkill, TextPart
from a2a.utils import new_agent_parts_message
from a2ui.a2a import create_a2ui_parts, get_a2ui_agent_extension, get_a2ui_messages, try_activate_a2ui_extension
from starlette.middleware.cors import CORSMiddleware

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("funder-agent")

VERSION = "1.0"
BASIC_CATALOG = "https://a2ui.org/specification/v1_0/catalogs/basic/catalog.json"
FUNDER_CATALOG = "https://funding.local/a2ui/catalogs/funder/v1/catalog.json"
PORT = 8091


def overview_surface(deal: dict[str, Any]) -> list[dict[str, Any]]:
    """Pure: deal data in, A2UI v1.0 messages out (the shape every fixed-surface builder will have)."""
    sid = "deal_overview"
    return [
        {"version": VERSION, "createSurface": {"surfaceId": sid, "catalogId": FUNDER_CATALOG}},  # extends the basic catalog
        {"version": VERSION, "updateComponents": {"surfaceId": sid, "components": [
            {"id": "root", "component": "Card", "child": "body"},
            {"id": "body", "component": "Column", "children": ["title", "status", "dealer", "assignee", "stips", "take_button"]},
            {"id": "title", "component": "Text", "text": {"@path": "/deal/loanApplicationId"}},
            {"id": "status", "component": "Text", "text": {"@call": "formatString", "args": {"value": "Status: ${/deal/status}"}}},
            {"id": "dealer", "component": "Text", "text": {"@path": "/deal/dealershipName"}},
            {"id": "assignee", "component": "Text", "text": {"@call": "formatString", "args": {"value": "Assigned to: ${/deal/assignedFunder}"}}},
            # From OUR catalog. (A per-component "@catalogId" isn't supported by the published web_core 0.15.0.)
            {"id": "stips", "component": "DataTable",
             "columns": [{"key": "code", "title": "Stip"}, {"key": "status", "title": "Status"}],
             "rows": {"@path": "/deal/stips"}, "emptyText": "No stips"},
            {"id": "take_label", "component": "Text", "text": "Take this deal"},
            {"id": "take_button", "component": "Button", "variant": "primary", "child": "take_label",
             "action": {"event": {"name": "deal.take", "context": {"loanApplicationId": {"@path": "/deal/loanApplicationId"}}}}},
        ]}},
        {"version": VERSION, "updateDataModel": {"surfaceId": sid, "path": "/deal", "value": deal}},
    ]


SPIKE_DEAL = {"loanApplicationId": "LA-1006", "status": "STIPULATED", "dealershipName": "Lone Star Motors",
              "assignedFunder": "nobody",
              "stips": [{"code": "STIP_PRICE_MISMATCH", "status": "PENDING"}]}


class FunderAgentExecutor(AgentExecutor):
    def __init__(self, card: AgentCard):
        self.card = card

    async def execute(self, context: RequestContext, event_queue: EventQueue) -> None:
        version = try_activate_a2ui_extension(context, self.card)
        incoming = get_a2ui_messages(context.message) if context.message else []
        actions = [m["action"] for m in incoming if "action" in m]
        log.info("a2ui=%s actions=%s", version, [a.get("name") for a in actions])
        if actions:
            replies = []
            for a in actions:
                if a.get("name") == "deal.take":  # a write, handled by code: later this calls the funder API
                    replies.append({"version": VERSION, "updateDataModel": {
                        "surfaceId": a["surfaceId"], "path": "/deal/assignedFunder", "value": "you (spike)"}})
            messages = replies
        else:
            messages = overview_surface(SPIKE_DEAL)
        await event_queue.enqueue_event(new_agent_parts_message(create_a2ui_parts(messages, version=VERSION)))

    async def cancel(self, context: RequestContext, event_queue: EventQueue) -> None:
        raise NotImplementedError


def build_app():
    card = AgentCard(
        name="Funder dashboard agent (spike)", description="Builds the funder dashboard's A2UI surfaces.",
        url=f"http://localhost:{PORT}", version="0.0.1",
        default_input_modes=["text"], default_output_modes=["text"],
        capabilities=AgentCapabilities(streaming=False, extensions=[
            get_a2ui_agent_extension(version=VERSION, supported_catalog_ids=[BASIC_CATALOG, FUNDER_CATALOG])]),
        skills=[AgentSkill(id="deal", name="Deal view", description="Shows a deal.", tags=["funder"])],
    )
    handler = DefaultRequestHandler(agent_executor=FunderAgentExecutor(card), task_store=InMemoryTaskStore())
    app = A2AStarletteApplication(agent_card=card, http_handler=handler).build()
    app.add_middleware(CORSMiddleware, allow_origin_regex=r"http://localhost:\d+", allow_methods=["*"], allow_headers=["*"])
    return app


if __name__ == "__main__":
    uvicorn.run(build_app(), host="localhost", port=PORT)
