"""AS-2.7 "What's been done": the checks and the milestones, in plain words (T166)."""
from __future__ import annotations

from datetime import datetime, timezone

import pytest

from conftest import OVERVIEW, VERSIONS, validate
from funder_agent.surfaces.activity import CHECK_NAMES, activity_model, actor_name, checks_model
from funder_agent.surfaces.overview import deal_model, overview_surface

NOW = datetime(2026, 11, 21, 12, 0, tzinfo=timezone.utc)


def entry(action, after=None, actor="system:funding-local-6", at="2026-11-21T18:47:19.685+00:00", i=1):
    return {"auditId": i, "action": action, "actor": actor, "afterState": after, "createdAt": at}


def result(check, outcome, attempt=1, current=True):
    return {"validationCheck": check, "outcome": outcome, "attempt": attempt, "isCurrent": current,
            "evaluatedAt": "2026-11-21T18:47:18.000+00:00"}


# ---------------------------------------------------------------- checks

def test_AS_2_7_everyCheckIsListedWithItsCurrentOutcomeInWords():
    rows = checks_model([result("PRICE", "FAIL"), result("TILA", "PASS"), result("VIN", "NEEDS_REVIEW"),
                         result("GAP", "ERROR", attempt=3), result("INCOME", "FAIL", current=False),
                         result("INCOME", "PASS", attempt=2)])
    assert [r["check"] for r in rows] == list(CHECK_NAMES.values())          # always all five, in one order
    by = {r["check"]: r for r in rows}
    assert (by["TILA math"]["label"], by["TILA math"]["outcome"]) == ("Passed", "done")
    assert (by["Price"]["label"], by["Price"]["outcome"]) == ("Failed", "refused")
    assert by["VIN and vehicle"]["label"] == "Needs review"
    assert by["GAP form"]["label"] == "Error" and by["GAP form"]["detail"] == "attempt 3 · 2026-11-21 18:47"
    assert by["Income"]["label"] == "Passed" and by["Income"]["detail"].startswith("attempt 2")   # the current attempt


@pytest.mark.parametrize("in_progress, word", [(False, "Not run yet"), (True, "Running")])
def test_AS_2_7_aCheckWithNoResultSaysSo(in_progress, word):
    assert {r["label"] for r in checks_model([], in_progress)} == {word}


# ---------------------------------------------------------------- activity wording

@pytest.mark.parametrize("action, after, title", [
    ("STATUS_CHANGED", {"status": "PENDING_REVIEW", "reason": "SUBMITTED"}, "Submitted for checks"),
    ("STATUS_CHANGED", {"status": "STIPULATED", "reason": "VALIDATION_OUTCOME"}, "Checks finished: stipulated"),
    ("STATUS_CHANGED", {"status": "APPROVED", "reason": "VALIDATION_OUTCOME"}, "Checks finished: approved"),
    ("STATUS_CHANGED", {"status": "FUNDED", "reason": "DISBURSED"}, "Funded"),
    ("STATUS_CHANGED", {"status": "REJECTED", "reason": "REJECTED"}, "Rejected"),
    ("STATUS_CHANGED", {"status": "EXPIRED", "reason": "EXPIRED"}, "Expired"),
    ("STATUS_CHANGED", {"status": "APPROVED", "reason": "FUNDER_APPROVED"}, "Status: approved (funder approved)"),
    ("CREATED", {"code": "STIP_PRICE_MISMATCH", "check": "PRICE", "status": "PENDING"}, "Stip raised: price mismatch"),
    ("CREATED", {"applicationVersionId": 9}, "New version created"),
    ("WAIVED", {"status": "WAIVED", "note": "1 cent, dealer confirmed"}, "Stip waived: 1 cent, dealer confirmed"),
    ("SATISFIED", {"status": "SATISFIED", "note": "auto-resolved by re-run"}, "Stip satisfied: auto-resolved by re-run"),
    ("ASSIGNEE_CHANGED", {"assignedFunder": "funder-2", "reason": "TAKEN"}, "Taken by funder-2"),
    ("ASSIGNEE_CHANGED", {"assignedFunder": "funder-1"}, "Assigned to funder-1"),
    ("ASSIGNEE_CHANGED", {"assignedFunder": None}, "Unassigned"),
    ("RERUN_REQUESTED", {"checks": ["INCOME"]}, "Re-run requested: INCOME"),
    ("DISBURSEMENT_COMPLETED", {"amount": "28450.00", "reference": "DSB-2CDD4A58"}, "Paid: DSB-2CDD4A58, $28,450.00"),
    ("DISBURSEMENT_RETRY_REQUESTED", None, "Payment retry requested"),
    ("SOMETHING_NEW", None, "Something new"),                                 # unknown actions still read as words
])
def test_AS_2_7_eachAuditEntryReadsAsPlainWords(action, after, title):
    assert activity_model([entry(action, after)])["items"][0]["title"] == title


@pytest.mark.parametrize("actor, shown", [
    ("system:funding-local-6", "System"), ("dev:submit", "Dealer submission"), ("funder-2", "funder-2"), (None, "Unknown")])
def test_AS_2_7_whoDidItIsAPersonTheSystemOrTheDealersSubmission(actor, shown):
    assert actor_name(actor) == shown


def test_AS_2_7_newestFirstAndBackToBackAssignmentsAreOneLine():
    audit = [
        entry("STATUS_CHANGED", {"status": "PENDING_REVIEW", "reason": "SUBMITTED"}, "dev:submit", "2026-11-21T18:47:16Z", 1),
        entry("CREATED", {"code": "STIP_PRICE_MISMATCH"}, at="2026-11-21T18:47:19Z", i=2),
        entry("ASSIGNEE_CHANGED", {"assignedFunder": None}, "supervisor-1", "2026-11-21T18:48:13Z", 3),
        entry("ASSIGNEE_CHANGED", {"assignedFunder": "funder-1", "reason": "TAKEN"}, "funder-1", "2026-11-21T18:48:14Z", 4),
        entry("ASSIGNEE_CHANGED", {"assignedFunder": "funder-2", "reason": "TAKEN"}, "funder-2", "2026-11-21T18:48:17Z", 5),
    ]
    items = activity_model(audit)["items"]
    assert [i["title"] for i in items] == ["Taken by funder-2 (after 3 changes)", "Stip raised: price mismatch",
                                           "Submitted for checks"]
    assert items[0] == {"when": "2026-11-21 18:48", "title": "Taken by funder-2 (after 3 changes)",
                        "actor": "funder-2", "tone": "neutral"}


def test_AS_2_7_atMostEightLinesAndTheRestAreCounted():
    audit = [entry("RERUN_REQUESTED", {"checks": ["INCOME"]}, at=f"2026-11-21T18:{m:02d}:00Z", i=m) for m in range(11)]
    model = activity_model(audit)
    assert len(model["items"]) == 8 and model["older"] == 3
    deal = deal_model(OVERVIEW, VERSIONS, NOW, audit=audit)
    assert deal["activityNote"] == "3 earlier events in the full audit trail."
    assert deal_model(OVERVIEW, VERSIONS, NOW, audit=audit[:2])["activityNote"] == ""


# ---------------------------------------------------------------- on the overview

def test_AS_2_7_theOverviewShowsWhatsBeenDoneAndPassesStrictValidation():
    deal = deal_model(OVERVIEW, VERSIONS, NOW, results=[result("PRICE", "FAIL")],
                      audit=[entry("CREATED", {"code": "STIP_PRICE_MISMATCH"})])
    messages = overview_surface(deal)
    validate(messages)
    components = {c["id"]: c for c in messages[1]["updateComponents"]["components"]}
    assert components["checks"] == {"id": "checks", "component": "CheckList", "title": "Checks",
                                    "items": {"@path": "/deal/checks"}, "weight": 1}
    assert components["activity"]["component"] == "Timeline"
    assert "done" in components["body"]["children"]
    assert deal["activity"][0]["title"] == "Stip raised: price mismatch"
