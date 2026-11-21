"""Opening a deal and the actions on its overview. Every write is code, after the funder's click (FR-03)."""
from __future__ import annotations

from typing import Any

from ..api import FunderApi
from ..surfaces.overview import data_update, deal_model, overview_surface, surface_id


def load(api: FunderApi, loan_id: str) -> dict[str, Any]:
    """Reads the deal from the funder API and shapes the overview's data model."""
    return deal_model(api.overview(loan_id), api.versions(loan_id))


def open_deal(api: FunderApi, loan_id: str) -> list[dict[str, Any]]:
    return overview_surface(load(api, loan_id))


def take(api: FunderApi, context: dict[str, Any]) -> list[dict[str, Any]]:
    """``deal.take``: assign the deal to the signed-in user, then re-read it (AS-10.3).
    T106 adds the confirmation step, and D2 the role rule (AS-11.2)."""
    version_id = int(context["applicationVersionId"])
    api.assign(version_id, api.me)
    return [data_update(surface_id(version_id), load(api, context["loanApplicationId"]))]


HANDLERS = {"deal.take": take}
