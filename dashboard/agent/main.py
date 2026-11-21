"""Spike D0 / T001: an A2A server whose executor answers with A2UI v1.0, routed in code (no LLM, no ADK).

- A text request ("open LA-1006") returns the deal overview surface.
- An A2UI `action` (e.g. the `deal.take` button) is handled by code and returns a data-model update.
This mirrors the official A2UI sample's own AgentExecutor; ADK will sit behind it only for the assistant.
"""
import logging
import re
from typing import Any

import httpx

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
FUNDER_API = "http://localhost:8080/api/v1"   # spec 001's funder API (T005: real data)
SPIKE_FUNDER = "spike-funder"                   # D1 replaces this with the signed-in user's token


def load_deal(loan_id: str) -> dict[str, Any]:
    """Reads the deal from the funder API and shapes the surface's data model."""
    with httpx.Client(base_url=FUNDER_API, timeout=10) as api:
        o = api.get(f"/applications/{loan_id}").raise_for_status().json()
        stips = api.get(f"/versions/{o['applicationVersionId']}/stipulations").raise_for_status().json()
    return {"loanApplicationId": o["loanApplicationId"], "applicationVersionId": o["applicationVersionId"],
            "status": o["status"], "dealershipName": o["dealershipName"],
            "assignedFunder": o["assignedFunder"] or "nobody",
            "stips": [{"code": st["stipCode"], "status": st["status"]} for st in stips]}


def take_deal(version_id: int) -> None:
    """A write, done by code after the funder's click (FR-03): assign the deal to the funder."""
    with httpx.Client(base_url=FUNDER_API, timeout=10) as api:
        api.put(f"/versions/{version_id}/assignee", json={"funderId": SPIKE_FUNDER},
                headers={"X-Funder-Id": SPIKE_FUNDER}).raise_for_status()


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
             "action": {"event": {"name": "deal.take", "context": {
                 "loanApplicationId": {"@path": "/deal/loanApplicationId"},
                 "applicationVersionId": {"@path": "/deal/applicationVersionId"}}}}},
        ]}},
        {"version": VERSION, "updateDataModel": {"surfaceId": sid, "path": "/deal", "value": deal}},
    ]


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
                if a.get("name") == "deal.take":
                    ctx = a.get("context", {})
                    take_deal(int(ctx["applicationVersionId"]))          # write through the funder API
                    replies.append({"version": VERSION, "updateDataModel": {  # then refresh from the API
                        "surfaceId": a["surfaceId"], "path": "/deal",
                        "value": load_deal(ctx["loanApplicationId"])}})
            messages = replies
        else:
            text = " ".join(p.root.text for p in context.message.parts if isinstance(p.root, TextPart))
            loan = re.search(r"LA-[0-9A-Z-]+", text)
            messages = overview_surface(load_deal(loan.group(0) if loan else "LA-1006"))
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
