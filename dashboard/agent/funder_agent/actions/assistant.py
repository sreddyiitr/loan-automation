"""``deal.summarize`` (spec 002 AS-8.1, AS-8.4; T154): the assistant panel, streamed. It yields
``(messages, final)``: "Summarizing…" at once (not final), then one final panel with the answer.
Each is a whole panel. The overview isn't touched, so it never waits for the model (NFR-02)."""
from __future__ import annotations

from typing import Any, AsyncIterator, Tuple

from ..api import ApiError, FunderApi, message_for
from ..assistant.service import SummaryService
from ..assistant.summary import summary_input
from ..catalog import VERSION
from ..surfaces.assistant import assistant_surface, surface_id
from ..surfaces.notice import notice_surface

Messages = list[dict[str, Any]]
Step = Tuple[Messages, bool]


async def summarize_deal(api: FunderApi, context: dict[str, Any], service: SummaryService) -> AsyncIterator[Step]:
    version_id = int(context["applicationVersionId"])
    yield assistant_surface(version_id, "working"), False
    if not await service.available():
        yield assistant_surface(version_id, "unavailable"), True
        return
    try:
        overview = api.overview(context["loanApplicationId"])
        data = summary_input(overview, api.stipulations(version_id), api.validation_results(version_id))
    except ApiError as e:            # e.g. signed out meanwhile: the panel goes, a notice says why
        yield [{"version": VERSION, "deleteSurface": {"surfaceId": surface_id(version_id)}},
               *notice_surface(message_for(e.reason))], True
        return
    answer = await service.summarize(data)
    yield assistant_surface(version_id, answer.kind, answer.summary), True


async def suggest_assignments(api: FunderApi, context: dict[str, Any], service: Any) -> AsyncIterator[Step]:
    """``assign.suggest`` (US-12): "Asking the assistant…" at once, then the suggestions. The funder API
    refuses a non-supervisor (``ROLE_NOT_ALLOWED``) when the team is read."""
    from ..assistant.assign import suggest, tools_for, deal_facts
    from ..surfaces.assign import assign_surface
    from datetime import datetime, timezone
    yield assign_surface("working"), False
    try:
        users = api.users()
        ready = api.worklist("ready")
    except ApiError as e:
        yield [{"version": VERSION, "deleteSurface": {"surfaceId": "assign_assistant"}},
               *notice_surface(message_for(e.reason))], True
        return
    up = await service.available()
    tools = tools_for(deal_facts(ready, users, datetime.now(timezone.utc)), users)
    result = await suggest(service.conversation(tools) if up else None, ready, users)
    roles = {u["id"]: u["role"] for u in users}
    yield assign_surface(result.mode, result.items, roles), True


DRAFT_WORDS = {"ok": "", "unavailable": "The assistant is unavailable right now. Write the description yourself.",
               "invalid": "The assistant couldn't produce a draft. Write the description yourself."}


async def draft_stip_text(api: FunderApi, context: dict[str, Any], service: Any) -> AsyncIterator[Step]:
    """``deal.draft_stip`` (AS-8.3): a proposed description for the picked code, written into
    ``/deal/stipDraft``; the Add stip form shows it for the funder to edit. Nothing is added."""
    from ..assistant.draft_stip import draft_input
    from ..surfaces.overview import surface_id
    version_id, code = int(context["applicationVersionId"]), str(context["code"])
    put = lambda value: [{"version": VERSION, "updateDataModel": {"surfaceId": surface_id(version_id),
                                                                  "path": "/deal/stipDraft", "value": value}}]
    yield put({"code": code, "state": "working", "text": "", "message": "Drafting…"}), False
    answer = None
    if await service.available():
        try:
            overview = api.overview(context["loanApplicationId"])
            labels = {c["code"]: c.get("description") or c["code"] for c in api.stip_codes()}
            data = draft_input(overview, api.stipulations(version_id), api.validation_results(version_id), code, labels.get(code, code))
        except ApiError as e:
            yield put({"code": code, "state": "invalid", "text": "", "message": message_for(e.reason)}), True
            return
        answer = await service.draft(data)
    kind = answer.kind if answer else "unavailable"
    yield put({"code": code, "state": kind, "text": answer.summary if answer else "", "message": DRAFT_WORDS[kind]}), True


STREAMED = {"deal.summarize": summarize_deal, "assign.suggest": suggest_assignments, "deal.draft_stip": draft_stip_text}
