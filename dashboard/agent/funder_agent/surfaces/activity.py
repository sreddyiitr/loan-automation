"""AS-2.7 "What's been done": the checks and the deal's milestones, in plain words (T166).

Built by code from the funder API's validation results and audit trail (no LLM, no API change).
The overview shows them with ``CheckList`` and ``Timeline``.
"""
from __future__ import annotations

from typing import Any

CHECKS = ["TILA", "PRICE", "VIN", "GAP", "INCOME"]
CHECK_NAMES = {"TILA": "TILA math", "PRICE": "Price", "VIN": "VIN and vehicle", "GAP": "GAP form", "INCOME": "Income"}
OUTCOMES = {  # outcome -> (word, tone); the word is always shown, the tone only groups (NFR-04)
    "PASS": ("Passed", "done"), "FAIL": ("Failed", "refused"),
    "NEEDS_REVIEW": ("Needs review", "waiting"), "ERROR": ("Error", "waiting"),
}
MAX_ACTIVITY = 8


def _when(ts: str | None) -> str:
    """'2026-11-21T18:47:19.685+00:00' -> '2026-11-21 18:47' (UTC, as the API stores it)."""
    return f"{ts[:10]} {ts[11:16]}" if ts and len(ts) >= 16 else (ts or "")


def _words(code: str | None) -> str:
    """'STIP_PRICE_MISMATCH' -> 'price mismatch'."""
    return (code or "").removeprefix("STIP_").replace("_", " ").lower()


def checks_model(results: list[dict[str, Any]], in_progress: bool = False) -> list[dict[str, str]]:
    """One row per check, its current result in words."""
    current = {r["validationCheck"]: r for r in results if r.get("isCurrent", True)}
    rows = []
    for check in CHECKS:
        r = current.get(check)
        if r is None:
            label, tone, detail = ("Running" if in_progress else "Not run yet"), "neutral", ""
        else:
            label, tone = OUTCOMES.get(r.get("outcome"), (str(r.get("outcome", "")).title(), "neutral"))
            detail = f"attempt {r.get('attempt', 1)} · {_when(r.get('evaluatedAt'))}"
        rows.append({"check": CHECK_NAMES[check], "outcome": tone, "label": label, "detail": detail})
    return rows


def actor_name(actor: str | None) -> str:
    """Who did it, for a funder: a person's id, 'System' for the workflow, 'Dealer submission' for intake."""
    if not actor:
        return "Unknown"
    if actor.startswith("system:"):
        return "System"
    if actor.startswith("dev:"):
        return "Dealer submission"
    return actor


def _event(entry: dict[str, Any]) -> tuple[str, str]:
    """(title, tone) for one audit entry, in plain words."""
    action, after = entry.get("action", ""), entry.get("afterState") or {}
    if action == "STATUS_CHANGED":
        status, reason = after.get("status", ""), after.get("reason", "")
        if reason == "SUBMITTED":
            return "Submitted for checks", "neutral"
        if reason == "VALIDATION_OUTCOME":
            return f"Checks finished: {_words(status)}", "done" if status == "APPROVED" else "waiting"
        if status == "FUNDED":
            return "Funded", "done"
        if status in ("REJECTED", "EXPIRED"):
            return f"{status.title()}" + (f" ({_words(reason)})" if reason and reason != status else ""), "refused"
        return f"Status: {_words(status)}" + (f" ({_words(reason)})" if reason else ""), "neutral"
    if action == "CREATED" and after.get("code"):
        return f"Stip raised: {_words(after['code'])}", "waiting"
    if action == "CREATED":
        return "New version created", "neutral"
    if action in ("WAIVED", "SATISFIED"):
        note = after.get("note")
        return f"Stip {action.lower()}" + (f": {note}" if note else ""), "done"
    if action == "ASSIGNEE_CHANGED":
        who = after.get("assignedFunder")
        if who is None:
            return "Unassigned", "neutral"
        return (f"Taken by {who}" if after.get("reason") == "TAKEN" else f"Assigned to {who}"), "neutral"
    if action == "RERUN_REQUESTED":
        checks = after.get("checks")
        return "Re-run requested" + (f": {', '.join(checks)}" if isinstance(checks, list) and checks else ""), "neutral"
    if action == "DISBURSEMENT_COMPLETED":
        amount = after.get("amount")
        money = f"${float(amount):,.2f}" if amount not in (None, "") else ""
        return "Paid" + (f": {after.get('reference')}" if after.get("reference") else "") + (f", {money}" if money else ""), "done"
    if action == "DISBURSEMENT_RETRY_REQUESTED":
        return "Payment retry requested", "neutral"
    if action.startswith("DISBURSEMENT_"):
        return f"Payment {_words(action.removeprefix('DISBURSEMENT_'))}", "refused" if "FAIL" in action else "neutral"
    return _words(action).capitalize() or "Change", "neutral"


def activity_model(audit: list[dict[str, Any]], limit: int = MAX_ACTIVITY) -> dict[str, Any]:
    """The milestones, newest first. Back-to-back assignment changes become one line (the last one),
    noting how many there were. Returns the items and how many older ones were left out."""
    ordered = sorted(audit, key=lambda e: (e.get("createdAt") or "", e.get("auditId") or 0))
    merged: list[tuple[dict[str, Any], int]] = []           # (entry, how many it stands for)
    for entry in ordered:
        if entry.get("action") == "ASSIGNEE_CHANGED" and merged and merged[-1][0].get("action") == "ASSIGNEE_CHANGED":
            merged[-1] = (entry, merged[-1][1] + 1)
        else:
            merged.append((entry, 1))
    items = []
    for entry, count in reversed(merged):
        title, tone = _event(entry)
        if count > 1:
            title += f" (after {count} changes)"
        items.append({"when": _when(entry.get("createdAt")), "title": title,
                      "actor": actor_name(entry.get("actor")), "tone": tone})
    return {"items": items[:limit], "older": max(0, len(items) - limit)}
