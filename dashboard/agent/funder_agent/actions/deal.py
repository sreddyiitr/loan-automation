"""Opening a deal and the actions on its overview. Every write is code, after the funder's click (FR-03)."""
from __future__ import annotations

import asyncio
from typing import Any, AsyncIterator

from .. import config
from ..api import ApiError, FunderApi, message_for
from ..surfaces.notice import notice_surface
from ..surfaces.overview import data_update, deal_model, overview_surface, surface_id
from ..surfaces.worklist import work_model, worklist_surface


def load(api: FunderApi, loan_id: str) -> dict[str, Any]:
    """Reads the deal from the funder API and shapes the overview's data model, including what's
    been done (AS-2.7: the check results and the audit trail)."""
    overview = api.overview(loan_id)
    version = overview["applicationVersionId"]
    return deal_model(overview, api.versions(loan_id), me=api.me,
                      results=api.validation_results(version), audit=api.audit(version),
                      stips=api.stipulations(version), stip_codes=api.stip_codes())


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


def _write_then_reload(api: FunderApi, context: dict[str, Any], write, done: str) -> list[dict[str, Any]]:
    """Do the write, then rebuild the overview from a re-read (the stip's new status and the deal's,
    e.g. FUNDED, AS-3.2). A refusal (e.g. 409 ALREADY_RESOLVED, AS-3.4) still refreshes, with a notice."""
    try:
        write()
        note = done
    except ApiError as e:
        note = message_for(e.reason)
    return overview_surface(load(api, str(context["loanApplicationId"])), replace=True) + notice_surface(note)


def resolve_stip(api: FunderApi, context: dict[str, Any]) -> list[dict[str, Any]]:
    """``deal.resolve_stip`` (AS-3.2): SATISFIED or WAIVED, with the funder's reason."""
    status = str(context["status"])
    return _write_then_reload(api, context, lambda: api.resolve_stip(
        int(context["applicationVersionId"]), int(context["stipId"]), status, str(context.get("reason", "")).strip()),
        f"Stip {status.lower()}.")


def add_stip(api: FunderApi, context: dict[str, Any]) -> list[dict[str, Any]]:
    """``deal.add_stip`` (AS-3.5): a code from GET /stip-codes and the funder's description."""
    return _write_then_reload(api, context, lambda: api.add_stip(
        int(context["applicationVersionId"]), str(context["code"]), str(context.get("description", "")).strip()),
        "Stip added.")


async def settle(api: FunderApi, context: dict[str, Any], interval: float | None = None,
                 limit: float | None = None) -> AsyncIterator[list[dict[str, Any]]]:
    """FR-10 (plan §3) after a stip write: the workflow acts on it after the API answers (a waive that
    funds the deal, AS-3.2, is APPLYING, then DISBURSING, then FUNDED), so the deal is re-read every
    ``interval`` seconds while it's in progress, for up to ``limit``. Each change goes out as a data
    update; the layout stays. The first re-read always happens: right after the write, the workflow
    may not have picked the signal up yet, so the deal can look settled when it isn't."""
    interval = config.FRESHNESS_INTERVAL_S if interval is None else interval
    limit = config.FRESHNESS_LIMIT_S if limit is None else limit
    loan_id = str(context["loanApplicationId"])
    sid = surface_id(int(context["applicationVersionId"]))
    last: dict[str, Any] | None = None
    waited = 0.0
    while waited < limit:
        await asyncio.sleep(interval)
        waited += interval
        try:
            deal = load(api, loan_id)
        except ApiError:
            return                    # e.g. signed out meanwhile: the screen keeps what it has
        if deal != last:
            yield [data_update(sid, deal)]
            last = deal
        if not deal.get("inProgress"):
            return


# Writes whose effect the workflow finishes after the API answers: the reply is followed by
# ``settle``'s updates (the server streams them, server.py).
SETTLING = {"deal.resolve_stip", "deal.add_stip"}

HANDLERS = {"deal.take": take, "deal.resolve_stip": resolve_stip, "deal.add_stip": add_stip, "deal.open": open_from_list, "deal.assign": assign}
