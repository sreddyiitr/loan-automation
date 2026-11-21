"""The ``deal_<versionId>_assistant`` surface (spec 002 AS-8.1, AS-8.4; T154): the assistant's panel,
drawn by code. The model's text is only ever data in it, shown as plain text: it can't add
components or actions (D-1). The summary is read-only."""
from __future__ import annotations

from typing import Any

from ..catalog import FUNDER_CATALOG_ID, VERSION

WORKING = "Summarizing…"
UNAVAILABLE = "The assistant is unavailable right now. Everything else on this deal works."
INVALID = "The assistant couldn't produce an answer. Try again."
DISCLOSURE = "Written by the assistant from this deal's data only. Check it against the deal before you act."

STATES = {"working", "ok", "unavailable", "invalid"}


def surface_id(version_id: int) -> str:
    return f"deal_{version_id}_assistant"


def assistant_model(state: str, summary: str = "") -> dict[str, Any]:
    assert state in STATES, state
    return {
        "state": state,
        "stateText": {"working": WORKING, "unavailable": UNAVAILABLE, "invalid": INVALID}.get(state, ""),
        "summary": summary if state == "ok" else "",
        "disclosure": DISCLOSURE if state == "ok" else "",
    }


def assistant_surface(version_id: int, state: str, summary: str = "") -> list[dict[str, Any]]:
    """The whole panel, rebuilt: deleteSurface (accepted even if the browser hasn't got it), then
    createSurface, updateComponents and updateDataModel. A second createSurface of the same id is an
    integrity error in A2UI, so each reply stands on its own and replies can follow each other."""
    sid = surface_id(version_id)
    components = [
        {"id": "root", "component": "Card", "child": "body"},
        {"id": "body", "component": "Column", "children": ["title", "state", "summary", "disclosure"]},
        {"id": "title", "component": "Text", "text": "Assistant: summary"},
        {"id": "state", "component": "Text", "text": {"@path": "/assistant/stateText"}},
        {"id": "summary", "component": "Text", "text": {"@path": "/assistant/summary"}},
        {"id": "disclosure", "component": "Text", "variant": "caption", "text": {"@path": "/assistant/disclosure"}},
    ]
    return [
        {"version": VERSION, "deleteSurface": {"surfaceId": sid}},
        {"version": VERSION, "createSurface": {"surfaceId": sid, "catalogId": FUNDER_CATALOG_ID}},
        {"version": VERSION, "updateComponents": {"surfaceId": sid, "components": components}},
        {"version": VERSION, "updateDataModel": {"surfaceId": sid, "path": "/assistant",
                                                 "value": assistant_model(state, summary)}},
    ]
