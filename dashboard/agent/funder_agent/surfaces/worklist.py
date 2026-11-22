"""The ``worklist`` surface, "My work" (spec 002 AS-1.8; D6a): what's assigned to the signed-in user and
what's ready for them to take, each deal with what's missing in plain words. Built by code."""
from __future__ import annotations

from datetime import datetime, timezone
from typing import Any

from ..catalog import FUNDER_CATALOG_ID, VERSION
from .overview import ROLE_WORDS, expiry_text

SURFACE_ID = "worklist"
OPEN_ACTION = "deal.open"

STIP_WORDS = {
    "STIP_TILA_MATH_DISCREPANCY": "TILA math doesn't add up",
    "STIP_PRICE_MISMATCH": "Price differs between the RISC and the Buyer's Order",
    "STIP_VIN_MISMATCH": "VIN or vehicle details differ between documents",
    "STIP_GAP_FORM_MISMATCH": "GAP form isn't valid for the state or date",
    "STIP_LENDER_MISMATCH": "Lender name doesn't match",
    "STIP_INCOME_VARIANCE_EXCEEDED": "Stated income is too far from verified income",
    "STIP_PROOF_OF_INCOME": "Proof of income needed",
}
CHECK_WORDS = {"TILA": "TILA", "PRICE": "Price", "VIN": "VIN", "GAP": "GAP", "INCOME": "Income"}


def stip_words(code: str, check: str | None) -> str:
    if code in STIP_WORDS:
        return STIP_WORDS[code]
    if code == "STIP_EXTRACTION_REVIEW_REQUIRED":
        return f"A field needs review ({CHECK_WORDS.get(check, check)} check)" if check else "A field needs review"
    return code.removeprefix("STIP_").replace("_", " ").capitalize()       # a manual stip's own code


def missing(row: dict[str, Any]) -> list[str]:
    """What's holding the deal, each in a few words (AS-1.8)."""
    out = [stip_words(s.get("code", ""), s.get("check")) for s in row.get("pendingStips") or []]
    for c in row.get("checksNotPassed") or []:
        name = CHECK_WORDS.get(c.get("check"), c.get("check"))
        out.append(f"{name} check couldn't finish" if c.get("outcome") == "ERROR" else f"{name} needs review")
    if row.get("riscIncomplete"):
        out.append("RISC incomplete")
    payment = row.get("payment")
    if payment == "FAILED":
        out.append("Payment failed")
    elif payment == "UNKNOWN":
        out.append("Payment outcome unknown")
    return out


def work_row(row: dict[str, Any], now: datetime) -> dict[str, Any]:
    holding = missing(row)
    return {
        "loanApplicationId": row["loanApplicationId"],
        "versionNumber": row.get("versionNumber"),
        "dealer": row.get("dealershipName") or "—",
        "status": row["status"],
        "missing": holding or ["Nothing missing: waiting on the system"],
        "routedTo": ROLE_WORDS.get(row.get("routedTo") or "", "Nobody") if row.get("routedTo") else "Nobody",
        "expiry": expiry_text(row.get("expiresAt"), row["status"], now),
    }


def work_model(mine: list[dict[str, Any]], ready: list[dict[str, Any]], now: datetime | None = None) -> dict[str, Any]:
    now = now or datetime.now(timezone.utc)
    return {"mine": [work_row(r, now) for r in mine], "ready": [work_row(r, now) for r in ready]}


def worklist_surface(model: dict[str, Any], supervisor: bool = False) -> list[dict[str, Any]]:
    """The whole surface, rebuilt each time (deleteSurface first), so "My work" always shows fresh lists."""
    components = [
        # One white card over the hero band, like the deal page; the hero already says "Your work".
        {"id": "root", "component": "Card", "child": "body"},
        {"id": "body", "component": "Column", "children": ["mine", *(["suggest_row"] if supervisor else []), "ready"]},
        *([{"id": "suggest_row", "component": "Row", "align": "center", "children": ["suggest_button"]},
           {"id": "suggest_label", "component": "Text", "text": "✦ Suggest assignments"},
           {"id": "suggest_button", "component": "Button", "variant": "primary", "child": "suggest_label",
            "accessibility": {"label": "Suggest assignments"},
            "action": {"event": {"name": "assign.suggest", "context": {}}}}] if supervisor else []),
        {"id": "mine", "component": "WorkList", "title": "Assigned to you", "items": {"@path": "/work/mine"},
         "emptyText": "Nothing assigned to you right now.", "openAction": OPEN_ACTION},
        {"id": "ready", "component": "WorkList", "title": "Ready for you to take", "items": {"@path": "/work/ready"},
         "emptyText": "Nothing is waiting for your specialty.", "openAction": OPEN_ACTION},
    ]
    return [
        {"version": VERSION, "deleteSurface": {"surfaceId": SURFACE_ID}},
        {"version": VERSION, "createSurface": {"surfaceId": SURFACE_ID, "catalogId": FUNDER_CATALOG_ID}},
        {"version": VERSION, "updateComponents": {"surfaceId": SURFACE_ID, "components": components}},
        {"version": VERSION, "updateDataModel": {"surfaceId": SURFACE_ID, "path": "/work", "value": model}},
    ]
