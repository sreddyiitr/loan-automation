"""Funder API ``reason`` → a plain message for the funder (spec 002 FR-06), from ``messages.yaml``."""
from __future__ import annotations

import logging
from functools import lru_cache
from pathlib import Path

import yaml

log = logging.getLogger("funder-agent")
MESSAGES = Path(__file__).resolve().parents[2] / "messages.yaml"
FALLBACK = "The funder API refused this ({reason}). Try again, or ask a supervisor."


@lru_cache(maxsize=1)
def messages() -> dict[str, str]:
    return yaml.safe_load(MESSAGES.read_text())["reasons"]


def message_for(reason: str) -> str:
    """The message for a reason. An unknown reason gets a generic message and is logged (FR-06)."""
    known = messages().get(reason)
    if known is None:
        log.warning("no message for funder API reason %s; add it to messages.yaml", reason)
        return FALLBACK.format(reason=reason)
    return known
