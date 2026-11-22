"""Opening a deal and the actions on its overview. Every write is code, after the funder's click (FR-03)."""
from __future__ import annotations

from typing import Any

from ..api import ApiError, FunderApi, message_for
from ..surfaces.notice import notice_surface
from ..surfaces.overview import deal_model, overview_surface
from ..surfaces.worklist import work_model, worklist_surface


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


def open_from_list(api: FunderApi, context: dict[str, Any]) -> list[dict[str, Any]]:
    """``deal.open``, sent by a My work card (AS-1.8, AS-1.5): the deal's overview."""
    return open_deal(api, str(context["loanApplicationId"]))


def my_work(api: FunderApi) -> list[dict[str, Any]]:
    """AS-1.8: what's assigned to the signed-in user and what's ready for them to take. The supervisor
    also gets "Suggest assignments" (US-12)."""
    supervisor = api.whoami().get("role") == "SUPERVISOR"
    return worklist_surface(work_model(api.worklist("mine"), api.worklist("ready")), supervisor=supervisor)


def assign(api: FunderApi, context: dict[str, Any]) -> list[dict[str, Any]]:
    """``deal.assign`` (AS-12.4): the supervisor confirmed one suggestion; the normal audited reassign,
    then My work, refreshed. A refusal (e.g. a specialist's stale screen) is a notice."""
    try:
        api.assign(int(context["applicationVersionId"]), str(context["assignee"]))
    except ApiError as e:
        return notice_surface(message_for(e.reason))
    return my_work(api) + notice_surface(f"{context.get('loanApplicationId', 'The deal')} is assigned to {context['assignee']}.")


HANDLERS = {"deal.take": take, "deal.open": open_from_list, "deal.assign": assign}
