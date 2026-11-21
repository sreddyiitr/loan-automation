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
        "rows": _DYNAMIC,
        "emptyText": _DYNAMIC,
        "weight": {"type": "number"},
        "accessibility": {"type": "object"},
    },
    "required": ["id", "component", "columns", "rows"],
})

FUNDER_COMPONENTS = [DATA_TABLE]


@lru_cache(maxsize=1)
def validation_catalog() -> Catalog:
    basic = BasicCatalog()
    return Catalog(FUNDER_CATALOG_ID, VERSION,
                   components=list(basic.components.values()) + FUNDER_COMPONENTS,
                   functions=list(basic.functions.values()))
