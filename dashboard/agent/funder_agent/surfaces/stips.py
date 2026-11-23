"""The deal's stipulations (spec 002 D3, AS-3.1–3.5): each stip in words, who resolved it and why,
and what the viewer may do with it. Built by code; the API still refuses anything it shouldn't (FR-11)."""
from __future__ import annotations

from typing import Any

from ..api.messages import message_for

SYSTEM_NOTE = "auto-resolved by re-run"      # spec 001 AS-8.8, written by the workflow
NOT_WAIVABLE = "Can't be waived: the dealer has to send a corrected RISC."
CHECK_WORDS = {"TILA": "TILA", "PRICE": "Price", "VIN": "VIN", "GAP": "GAP", "INCOME": "Income"}


def allowed(overview: dict[str, Any], action: str) -> tuple[bool, str]:
    """FR-05b: whether the API allows ``action`` for this viewer, and its reason if not."""
    for a in overview.get("allowedActions") or []:
        if a.get("action") == action:
            return bool(a.get("allowed")), a.get("reason") or ""
    return overview.get("allowedActions") is None, ""


def stip_row(s: dict[str, Any], words: dict[str, str], can_resolve: bool) -> dict[str, Any]:
    pending = s.get("status") == "PENDING"
    waivable = bool(s.get("waivable", True))
    by = s.get("resolvedBy")
    system = s.get("resolutionNote") == SYSTEM_NOTE
    doc = s.get("triggeredByDocumentType")
    check = s.get("validationCheck")
    return {
        "stipId": s["stipId"],
        "code": s.get("stipCode", ""),
        "title": words.get(s.get("stipCode", ""), s.get("stipCode", "")),
        "description": s.get("description") or "",
        "raisedBy": (f"{CHECK_WORDS.get(check, check)} check" if check else "Added by a funder")
                    + (f" · {doc.replace('_', ' ')}" if doc else ""),
        "status": s.get("status", ""),
        "resolved": "" if pending else (
            f"Satisfied by the system after a passing re-run · {(s.get('resolvedAt') or '')[:10]}" if system else
            f"{s['status'].capitalize()} by {by or 'someone'} · {(s.get('resolvedAt') or '')[:10]}"
            + (f" · “{s['resolutionNote']}”" if s.get("resolutionNote") else "")),
        # AS-3.3, spec 001 AS-8.10: a RISC stip is resolved only by a corrected RISC that passes, so the
        # API refuses Satisfy as well as Waive for it; neither is offered.
        "canSatisfy": pending and can_resolve and waivable,
        "canWaive": pending and can_resolve and waivable,
        "note": NOT_WAIVABLE if pending and not waivable else "",
    }


def stips_model(overview: dict[str, Any], stips: list[dict[str, Any]], codes: list[dict[str, Any]]) -> dict[str, Any]:
    words = {c["code"]: c.get("description") or c["code"] for c in codes}
    can_resolve, _ = allowed(overview, "RESOLVE_STIP")
    can_add, why = allowed(overview, "ADD_STIP")
    return {
        "stips": [stip_row(s, words, can_resolve) for s in stips],
        "stipCodes": [{"value": c["code"], "label": words[c["code"]]} for c in codes],
        "canAddStip": can_add,
        "stipDraft": {"code": "", "state": "", "text": "", "message": ""},       # AS-8.3, filled by deal.draft_stip
        "addStipReason": "" if can_add else message_for(why) if why else "You can't add a stip to this deal.",
    }


def stip_list(resolve_action: str, add_action: str) -> dict[str, Any]:
    return {"id": "stips", "component": "StipList", "title": "Stipulations", "items": {"@path": "/deal/stips"},
            "codes": {"@path": "/deal/stipCodes"}, "canAdd": {"@path": "/deal/canAddStip"},
            "addReason": {"@path": "/deal/addStipReason"}, "emptyText": "No stips on this version.",
            "loanApplicationId": {"@path": "/deal/loanApplicationId"},
            "applicationVersionId": {"@path": "/deal/applicationVersionId"},
            "resolveAction": resolve_action, "addAction": add_action,
            "draft": {"@path": "/deal/stipDraft"}, "draftAction": "deal.draft_stip"}
