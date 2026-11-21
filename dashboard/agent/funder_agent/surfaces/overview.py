"""The deal overview surface, ``deal_<versionId>_overview`` (spec 002 US-2, AS-2.1–2.3, AS-2.5).

``deal_model`` shapes the funder API's answers into the data model, and ``overview_surface``
lays the components out over it. Text is formatted here, in code, so the surface stays simple and
every value can be tested.
"""
from __future__ import annotations

from datetime import datetime, timezone
from decimal import Decimal
from typing import Any

from .. import config
from ..catalog import FUNDER_CATALOG_ID, VERSION

TERMINAL = {"FUNDED", "REJECTED", "EXPIRED"}

PAYMENT_TEXT = {
    "NOT_STARTED": "Not started",
    "IN_FLIGHT": "Payment in progress",
    "FAILED": "Payment failed: the disbursement API refused it",
    "UNKNOWN": "Payment outcome unknown: retry the payment to find out",
}


def surface_id(version_id: int) -> str:
    return f"deal_{version_id}_overview"


def money(value: Any) -> str:
    return "—" if value is None else f"${Decimal(str(value)):,.2f}"


def _date(value: str | None) -> str:
    return value[:10] if value else "—"


def _parse(ts: str) -> datetime:
    return datetime.fromisoformat(ts.replace("Z", "+00:00"))


def expiry_text(expires_at: str | None, status: str, now: datetime) -> str:
    """AS-2.1 "days to expiry"."""
    if status == "EXPIRED":
        return f"Expired on {_date(expires_at)}"
    if not expires_at:
        return "—"
    days = (_parse(expires_at) - now).total_seconds() / 86400
    if days < 1:
        return f"Expires today ({_date(expires_at)})"
    whole = int(days)
    return f"{whole} day{'s' if whole != 1 else ''} left ({_date(expires_at)})"


def payment_text(payment: dict[str, Any]) -> str:
    """AS-2.1 payment status: not started, paid with reference and amount, failed, or outcome unknown."""
    state = payment.get("state", "NOT_STARTED")
    if state == "PAID":
        return f"Paid: {payment.get('reference') or 'no reference'}, {money(payment.get('amount'))}"
    return PAYMENT_TEXT.get(state, state)


def version_rows(versions: list[dict[str, Any]]) -> list[dict[str, str]]:
    """AS-2.3: every version, newest first; earlier ones are marked "superseded by vN"."""
    ordered = sorted(versions, key=lambda v: v["versionNumber"], reverse=True)
    latest = next((v["versionNumber"] for v in ordered if v.get("isLatestVersion")), None)
    return [{
        "version": f"v{v['versionNumber']}",
        "status": v.get("versionStatus") or "—",
        "submitted": _date(v.get("submittedAt")),
        "note": "latest" if v.get("isLatestVersion") else f"superseded by v{latest}" if latest else "",
    } for v in ordered]


def temporal_url(workflow_id: str) -> str:
    return f"{config.TEMPORAL_UI_URL}/namespaces/{config.TEMPORAL_NAMESPACE}/workflows/{workflow_id}"


def deal_model(overview: dict[str, Any], versions: list[dict[str, Any]], now: datetime | None = None,
               me: str | None = None) -> dict[str, Any]:
    """``me`` is the signed-in user (the token's sub): their own deal reads "you"."""
    now = now or datetime.now(timezone.utc)
    assignee = overview.get("assignedFunder")
    return {
        "loanApplicationId": overview["loanApplicationId"],
        "applicationVersionId": overview["applicationVersionId"],
        "versionNumber": overview.get("latestVersionNumber"),
        "status": overview["status"],
        "dealershipName": overview.get("dealershipName") or "—",
        "borrowerName": overview.get("borrowerName") or "—",
        "coBorrowerName": overview.get("coBorrowerName") or "None",
        "statedIncome": money(overview.get("statedIncomeAnnual")),
        "vehicleVin": overview.get("vehicleVin") or "—",
        "assignedFunder": f"{assignee} (you)" if assignee and assignee == me else assignee or "Unassigned",
        "canTake": assignee is None and overview["status"] not in TERMINAL,
        "submitted": _date(overview.get("submittedAt")),
        "expiry": expiry_text(overview.get("expiresAt"), overview["status"], now),
        "payment": payment_text(overview.get("payment") or {}),
        "inProgress": bool(overview.get("inProgress")),
        "progressText": "Checks or a payment are running; this page refreshes when they finish."
        if overview.get("inProgress") else "",
        "temporalUrl": temporal_url(overview["workflowId"]) if overview.get("workflowId") else "",
        "versions": version_rows(versions),
    }


def _fact(cid: str, label: str, path: str) -> dict[str, Any]:
    return {"id": cid, "component": "Text", "text": {"@call": "formatString", "args": {"value": f"{label}: ${{/deal/{path}}}"}}}


FACTS = [("dealer", "Dealer", "dealershipName"), ("borrower", "Borrower", "borrowerName"),
         ("co_borrower", "Co-borrower", "coBorrowerName"), ("income", "Stated income", "statedIncome"),
         ("vin", "VIN", "vehicleVin"), ("assignee", "Assigned to", "assignedFunder"),
         ("submitted", "Submitted", "submitted"), ("expiry", "Expiry", "expiry"), ("payment", "Payment", "payment")]


def _take_controls() -> list[dict[str, Any]]:
    """AS-2.6: Take opens a confirmation (a Modal); only its confirm button sends ``deal.take``.
    The trigger button's own event is UI-only (``ui.``), which the agent ignores."""
    return [
        {"id": "take_modal", "component": "Modal", "trigger": "take_button", "content": "take_confirm"},
        {"id": "take_label", "component": "Text", "text": "Take this deal"},
        {"id": "take_button", "component": "Button", "variant": "primary", "child": "take_label",
         "accessibility": {"label": "Take this deal"},
         "action": {"event": {"name": "ui.confirm_take", "context": {}}}},
        {"id": "take_confirm", "component": "Column", "children": ["take_question", "take_confirm_button"]},
        {"id": "take_question", "component": "Text", "text": {"@call": "formatString", "args": {
            "value": "Take ${/deal/loanApplicationId} v${/deal/versionNumber}? It will be assigned to you."}}},
        {"id": "take_confirm_label", "component": "Text", "text": "Yes, take it"},
        {"id": "take_confirm_button", "component": "Button", "variant": "primary", "child": "take_confirm_label",
         "accessibility": {"label": "Yes, take it"},
         "action": {"event": {"name": "deal.take", "context": {
             "loanApplicationId": {"@path": "/deal/loanApplicationId"},
             "applicationVersionId": {"@path": "/deal/applicationVersionId"}}}}},
    ]


def overview_surface(deal: dict[str, Any], replace: bool = False) -> list[dict[str, Any]]:
    """Pure: the deal model in, A2UI v1.0 messages out (createSurface, updateComponents, updateDataModel).

    The layout depends on the deal (Take only when it's unassigned). ``replace=True`` rebuilds a
    surface the browser already has: ``deleteSurface`` first, which also closes an open Modal.
    """
    sid = surface_id(deal["applicationVersionId"])
    facts = [_fact(*f) for f in FACTS]
    take = _take_controls() if deal.get("canTake") else []
    components = [
        {"id": "root", "component": "Card", "child": "body"},
        {"id": "body", "component": "Column",
         "children": ["title", "status", "progress", *[f["id"] for f in facts], "actions", "history_title", "history"]},
        {"id": "title", "component": "Text",
         "text": {"@call": "formatString", "args": {"value": "${/deal/loanApplicationId} · v${/deal/versionNumber}"}}},
        {"id": "status", "component": "StatusBadge", "status": {"@path": "/deal/status"}},
        {"id": "progress", "component": "Text", "variant": "caption", "text": {"@path": "/deal/progressText"}},
        *facts,
        {"id": "actions", "component": "Row",
         "children": [*(["take_modal"] if take else []), "summarize_button", "temporal_button"]},
        *take,
        # AS-8.1: read-only, so it's on every deal and needs no confirmation; the panel is its own surface.
        {"id": "summarize_label", "component": "Text", "text": "Summarize"},
        {"id": "summarize_button", "component": "Button", "child": "summarize_label",
         "accessibility": {"label": "Summarize"},
         "action": {"event": {"name": "deal.summarize", "context": {
             "loanApplicationId": {"@path": "/deal/loanApplicationId"},
             "applicationVersionId": {"@path": "/deal/applicationVersionId"}}}}},
        {"id": "temporal_label", "component": "Text", "text": "Open the workflow in Temporal"},
        {"id": "temporal_button", "component": "Button", "variant": "borderless", "child": "temporal_label",
         "accessibility": {"label": "Open the workflow in Temporal"},
         "action": {"functionCall": {"@call": "openUrl", "args": {"url": {"@path": "/deal/temporalUrl"}}}}},
        {"id": "history_title", "component": "Text", "text": "Version history"},
        {"id": "history", "component": "DataTable",
         "columns": [{"key": "version", "title": "Version"}, {"key": "status", "title": "Status"},
                     {"key": "submitted", "title": "Submitted"}, {"key": "note", "title": ""}],
         "rows": {"@path": "/deal/versions"}, "emptyText": "No versions"},
    ]
    return [
        *([{"version": VERSION, "deleteSurface": {"surfaceId": sid}}] if replace else []),
        {"version": VERSION, "createSurface": {"surfaceId": sid, "catalogId": FUNDER_CATALOG_ID}},
        {"version": VERSION, "updateComponents": {"surfaceId": sid, "components": components}},
        data_update(sid, deal),
    ]


def data_update(sid: str, deal: dict[str, Any]) -> dict[str, Any]:
    """Refreshes the deal's data only; the layout stays (FR-10)."""
    return {"version": VERSION, "updateDataModel": {"surfaceId": sid, "path": "/deal", "value": deal}}
