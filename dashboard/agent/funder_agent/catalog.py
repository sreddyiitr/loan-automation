"""Our A2UI catalog, agent side (plan §6, NFR-05).

``web_core`` 0.15.0 lets a surface use one catalog only, so ours *extends* the Basic Catalog: all
of its components and functions, plus ours. The web app's ``funderCatalog.tsx`` registers the same
components; keep the two in step. ``validation_catalog()`` is what the tests check surfaces against.
"""
from __future__ import annotations

from functools import lru_cache

from a2ui.core import Catalog
from a2ui.core.basic_catalog.v1_0 import BasicCatalog
from a2ui.core.catalog import ComponentApi

VERSION = "1.0"
BASIC_CATALOG_ID = "https://a2ui.org/specification/v1_0/catalogs/basic/catalog.json"
FUNDER_CATALOG_ID = "https://funding.local/a2ui/catalogs/funder/v1/catalog.json"

_DYNAMIC = {}   # a literal, an {"@path": ...} binding or an {"@call": ...}; resolved by the renderer

DATA_TABLE = ComponentApi("DataTable", {
    "type": "object",
    "description": "A read-only table. rows is usually bound to a list in the data model with @path.",
    "additionalProperties": False,
    "properties": {
        "id": {"type": "string"},
        "component": {"const": "DataTable"},
        "columns": {"type": "array", "minItems": 1, "items": {
            "type": "object", "additionalProperties": False, "required": ["key", "title"],
            "properties": {"key": {"type": "string"}, "title": {"type": "string"}}}},
        "title": _DYNAMIC,
        "rows": _DYNAMIC,
        "emptyText": _DYNAMIC,
        "weight": {"type": "number"},
        "accessibility": {"type": "object"},
    },
    "required": ["id", "component", "columns", "rows"],
})

STATUS_BADGE = ComponentApi("StatusBadge", {
    "type": "object",
    "description": "The version status as a badge. The status is always written out, never shown by colour alone (NFR-04).",
    "additionalProperties": False,
    "properties": {
        "id": {"type": "string"},
        "component": {"const": "StatusBadge"},
        "status": _DYNAMIC,
        "weight": {"type": "number"},
        "accessibility": {"type": "object"},
    },
    "required": ["id", "component", "status"],
})

HEADING = ComponentApi("Heading", {
    "type": "object",
    "description": "A title (D1c). The basic Text allows only caption and body, so titles need their own component.",
    "additionalProperties": False,
    "properties": {
        "id": {"type": "string"},
        "component": {"const": "Heading"},
        "text": _DYNAMIC,
        "level": {"type": "integer", "minimum": 1, "maximum": 3},
        "weight": {"type": "number"},
        "accessibility": {"type": "object"},
    },
    "required": ["id", "component", "text"],
})

FACT_LIST = ComponentApi("FactList", {
    "type": "object",
    "description": "Label/value pairs as a two-column grid (D1c). items is usually bound to a list of {label, value}.",
    "additionalProperties": False,
    "properties": {
        "id": {"type": "string"},
        "component": {"const": "FactList"},
        "items": _DYNAMIC,
        "weight": {"type": "number"},
        "accessibility": {"type": "object"},
    },
    "required": ["id", "component", "items"],
})

def _list_component(name: str, description: str) -> ComponentApi:
    return ComponentApi(name, {
        "type": "object", "description": description, "additionalProperties": False,
        "properties": {
            "id": {"type": "string"}, "component": {"const": name},
            "title": _DYNAMIC, "items": _DYNAMIC, "emptyText": _DYNAMIC,
            "weight": {"type": "number"}, "accessibility": {"type": "object"},
        },
        "required": ["id", "component", "items"],
    })


CHECK_LIST = _list_component(
    "CheckList", "One row per check with its outcome in words (AS-2.7). items: a list of {check, outcome, label, detail}.")
TIMELINE = _list_component(
    "Timeline", "Events, newest first, each with when, what and who (AS-2.7). items: a list of {when, title, actor, tone}.")

WORK_LIST = ComponentApi("WorkList", {
    "type": "object",
    "description": "Deals as clickable cards (AS-1.8). items: a list of {loanApplicationId, dealer, status, missing, "
                   "routedTo, expiry}; clicking a card sends openAction with the row's loanApplicationId.",
    "additionalProperties": False,
    "properties": {
        "id": {"type": "string"}, "component": {"const": "WorkList"},
        "title": _DYNAMIC, "items": _DYNAMIC, "emptyText": _DYNAMIC,
        "openAction": {"type": "string"},
        "weight": {"type": "number"}, "accessibility": {"type": "object"},
    },
    "required": ["id", "component", "items", "openAction"],
})

SUGGESTION_LIST = ComponentApi("SuggestionList", {
    "type": "object",
    "description": "Assignment suggestions (US-12): each row has its own Assign, confirmed in place, which sends "
                   "assignAction with {loanApplicationId, applicationVersionId, assignee}.",
    "additionalProperties": False,
    "properties": {
        "id": {"type": "string"}, "component": {"const": "SuggestionList"},
        "items": _DYNAMIC, "emptyText": _DYNAMIC, "assignAction": {"type": "string"},
        "weight": {"type": "number"}, "accessibility": {"type": "object"},
    },
    "required": ["id", "component", "items", "assignAction"],
})

FUNDER_COMPONENTS = [DATA_TABLE, STATUS_BADGE, HEADING, FACT_LIST, CHECK_LIST, TIMELINE, WORK_LIST, SUGGESTION_LIST]


@lru_cache(maxsize=1)
def validation_catalog() -> Catalog:
    basic = BasicCatalog()
    return Catalog(FUNDER_CATALOG_ID, VERSION,
                   components=list(basic.components.values()) + FUNDER_COMPONENTS,
                   functions=list(basic.functions.values()))
