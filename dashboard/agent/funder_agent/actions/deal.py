"""Opening a deal and the actions on its overview. Every write is code, after the funder's click (FR-03)."""
from __future__ import annotations

from typing import Any

from ..api import ApiError, FunderApi, message_for
from ..surfaces.notice import notice_surface
from ..surfaces.overview import deal_model, overview_surface


def load(api: FunderApi, loan_id: str) -> dict[str, Any]:
    """Reads the deal from the funder API and shapes the overview's data model, including what's
    been done (AS-2.7: the check results and the audit trail)."""
    overview = api.overview(loan_id)
    version = overview["applicationVersionId"]
    return deal_model(overview, api.versions(loan_id), me=api.me,
                      results=api.validation_results(version), audit=api.audit(version))


def open_deal(api: FunderApi, loan_id: str) -> list[dict[str, Any]]:
    return overview_surface(load(api, loan_id))


def take(api: FunderApi, context: dict[str, Any]) -> list[dict[str, Any]]:
    """``deal.take``, sent by the confirmation's button (AS-2.6): take the deal, then rebuild the
    overview from a re-read, since Take is no longer offered. If the API refuses (e.g. someone else
    took it first: 409 ALREADY_ASSIGNED), the overview is still rebuilt, so it shows who has it,
    and a notice says why. D2 adds the role rule (AS-11.2)."""
    loan_id = context["loanApplicationId"]
    refused: ApiError | None = None
    try:
        api.take(int(context["applicationVersionId"]))
    except ApiError as e:
        refused = e
    replies = overview_surface(load(api, loan_id), replace=True)
    return replies + (notice_surface(message_for(refused.reason)) if refused else [])


HANDLERS = {"deal.take": take}
