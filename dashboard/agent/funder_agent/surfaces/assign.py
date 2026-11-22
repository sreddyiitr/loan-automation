"""The ``assign_assistant`` panel (spec 002 US-12; T255): the assistant's assignment suggestions, drawn
by code beside My work. Each suggestion has its own Assign, confirmed in place (AS-12.4); nothing is
assigned without that click, and there's no "apply all"."""
from __future__ import annotations

import re
from typing import Any

from ..catalog import FUNDER_CATALOG_ID, VERSION
from .overview import ROLE_WORDS

SURFACE_ID = "assign_assistant"
ASSIGN_ACTION = "deal.assign"

NOTES = {
    "working": "Asking the assistant…",
    "assistant": "Suggested by the assistant (local model) from each deal's routing, urgency and workload. "
                 "Code dropped anything not allowed. Nothing is assigned until you confirm.",
    "plan": "The assistant isn't available, so these come from code's own plan: most urgent first, each deal "
            "to the eligible person with the fewest open deals.",
    "invalid": "The assistant couldn't produce usable suggestions, so these come from code's own plan.",
}


KIND_WORDS = {"GAP": "GAP", "INCOME": "Income", "RISC": "RISC", "DOCUMENTS": "Documents", "PAYMENT": "Payment",
              "GENERAL": "General"}


def plain(reason: str) -> str:
    """The model sometimes echoes codes (DOCUMENTS_SPECIALIST, DOCUMENTS work): show them as words."""
    for code, words in ROLE_WORDS.items():
        mid = words if words.split()[0].isupper() else words[0].lower() + words[1:]     # "RISC specialist" stays
        reason = reason.replace(code, words if reason.startswith(code) else mid)
    return re.sub(r"\b(" + "|".join(KIND_WORDS) + r")\b(?= work)", lambda m: KIND_WORDS[m.group(1)], reason)


def assign_model(state: str, items: list[dict[str, Any]], roles: dict[str, str] | None = None) -> dict[str, Any]:
    roles = roles or {}
    rows = [i | {"reason": plain(i["reason"]), "assigneeRole": ROLE_WORDS.get(roles.get(i["assignee"], ""), ""),
                 "sourceText": "Assistant" if i["source"] == "assistant" else "Code's plan"} for i in items]
    return {"state": state, "note": NOTES[state], "items": rows,
            "empty": "No unassigned deals need anyone right now." if state != "working" else ""}


def assign_surface(state: str, items: list[dict[str, Any]] | None = None, roles: dict[str, str] | None = None) -> list[dict[str, Any]]:
    """The whole panel, rebuilt each time (deleteSurface first), with only the parts this state uses."""
    lines = ["note"] if state == "working" else ["note", "list"]
    every = {
        "note": {"id": "note", "component": "Text", "variant": "caption", "text": {"@path": "/assign/note"}},
        "list": {"id": "list", "component": "SuggestionList", "items": {"@path": "/assign/items"},
                 "emptyText": {"@path": "/assign/empty"}, "assignAction": ASSIGN_ACTION},
    }
    components = [
        {"id": "root", "component": "Card", "child": "body"},
        {"id": "body", "component": "Column", "children": ["title", *lines]},
        {"id": "title", "component": "Heading", "level": 2, "text": "Assignment suggestions"},
        *(every[line] for line in lines),
    ]
    return [
        {"version": VERSION, "deleteSurface": {"surfaceId": SURFACE_ID}},
        {"version": VERSION, "createSurface": {"surfaceId": SURFACE_ID, "catalogId": FUNDER_CATALOG_ID}},
        {"version": VERSION, "updateComponents": {"surfaceId": SURFACE_ID, "components": components}},
        {"version": VERSION, "updateDataModel": {"surfaceId": SURFACE_ID, "path": "/assign",
                                                 "value": assign_model(state, items or [], roles)}},
    ]
