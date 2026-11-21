"""The ``notice`` surface: one plain message for the funder (signed out, not found, refused; FR-06)."""
from __future__ import annotations

from typing import Any

from ..catalog import FUNDER_CATALOG_ID, VERSION

SURFACE_ID = "notice"


def notice_surface(message: str) -> list[dict[str, Any]]:
    return [
        {"version": VERSION, "createSurface": {"surfaceId": SURFACE_ID, "catalogId": FUNDER_CATALOG_ID}},
        {"version": VERSION, "updateComponents": {"surfaceId": SURFACE_ID, "components": [
            {"id": "root", "component": "Card", "child": "message"},
            {"id": "message", "component": "Text", "text": {"@path": "/notice/message"}},
        ]}},
        {"version": VERSION, "updateDataModel": {"surfaceId": SURFACE_ID, "path": "/notice", "value": {"message": message}}},
    ]
