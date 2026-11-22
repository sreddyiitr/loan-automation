"""NFR-05: every fixed surface passes A2UI v1.0 strict validation; and the overview's data model (US-2)."""
import copy
from datetime import datetime, timezone

import pytest
from a2ui.core import A2uiValidationError

from conftest import OVERVIEW, VERSIONS, validate
from funder_agent.surfaces.notice import notice_surface
from funder_agent.surfaces.overview import deal_model, expiry_text, overview_surface, payment_text, surface_id

NOW = datetime(2026, 11, 21, 12, 0, tzinfo=timezone.utc)


def test_NFR_05_overviewSurfaceIsValidA2ui():
    validate(overview_surface(deal_model(OVERVIEW, VERSIONS, NOW)))


def test_NFR_05_overviewWithoutTakeIsValidA2ui():
    validate(overview_surface(deal_model({**OVERVIEW, "assignedFunder": "funder-9"}, VERSIONS, NOW)))


def test_NFR_04_everyButtonHasAnAccessibleLabel():
    components = overview_surface(deal_model(OVERVIEW, VERSIONS, NOW))[1]["updateComponents"]["components"]
    buttons = [c for c in components if c["component"] == "Button"]
    assert buttons and all(c.get("accessibility", {}).get("label") for c in buttons)


def _keys(node):
    if isinstance(node, dict):
        for k, v in node.items():
            yield k
            yield from _keys(v)
    elif isinstance(node, list):
        for v in node:
            yield from _keys(v)


@pytest.mark.parametrize("deal", [OVERVIEW, {**OVERVIEW, "assignedFunder": "funder-9"}])
def test_NFR_05_noReservedKeysTheWebRendererRejects(deal):
    """a2ui-core accepts "@accessibility" and "@catalogId", but the pinned web renderer (web_core
    0.15.0) rejects both on components ("Unrecognized key"), and one bad prop blanks the surface.
    So the Python validator can't catch these; this test does (a2ui-notes §12d)."""
    components = overview_surface(deal_model(deal, VERSIONS, NOW))[1]["updateComponents"]["components"]
    assert not {"@accessibility", "@catalogId"} & set(_keys(components))


@pytest.mark.parametrize("assignee, status, offered", [
    (None, "STIPULATED", True), (None, "PENDING_REVIEW", True), ("funder-9", "STIPULATED", False),
    ("docs-1", "STIPULATED", False), (None, "FUNDED", False), (None, "EXPIRED", False),
])
def test_AS_2_6_takeIsOfferedOnlyOnAnUnassignedOpenDeal(assignee, status, offered):
    deal = deal_model({**OVERVIEW, "assignedFunder": assignee, "status": status}, VERSIONS, NOW, me="docs-1")
    ids = {c["id"] for c in overview_surface(deal)[1]["updateComponents"]["components"]}
    assert deal["canTake"] is offered and ("take_modal" in ids) is offered


def test_AS_2_6_yourOwnDealSaysYou():
    assert deal_model({**OVERVIEW, "assignedFunder": "docs-1"}, VERSIONS, NOW, me="docs-1")["assignedFunder"] == "docs-1 (you)"


def test_NFR_05_noticeSurfaceIsValidA2ui():
    validate(notice_surface("Sign in to use the funder dashboard."))


@pytest.mark.parametrize("break_it", [
    lambda c: c.__setitem__("variant", "h2"),            # Text allows only caption | body (D0 pitfall)
    lambda c: c.__setitem__("component", "Nope"),        # not in our catalog
])
def test_NFR_05_theValidatorReallyRejects(break_it):
    messages = overview_surface(deal_model(OVERVIEW, VERSIONS, NOW))
    bad = copy.deepcopy(messages)
    title = next(c for c in bad[1]["updateComponents"]["components"] if c["id"] == "subtitle")
    break_it(title)
    with pytest.raises(A2uiValidationError):
        validate(bad)


def test_AS_2_1_statusIsAStatusBadgeBoundToTheDeal():
    components = overview_surface(deal_model(OVERVIEW, VERSIONS, NOW))[1]["updateComponents"]["components"]
    assert next(c for c in components if c["id"] == "status") == \
        {"id": "status", "component": "StatusBadge", "status": {"@path": "/deal/status"}}


def test_AS_2_1_overviewShowsTheDealFacts():
    deal = deal_model(OVERVIEW, VERSIONS, NOW)
    assert deal["loanApplicationId"] == "LA-1006" and deal["versionNumber"] == 2
    assert deal["statedIncome"] == "$85,000.00"
    assert deal["coBorrowerName"] == "None"
    assert deal["assignedFunder"] == "Unassigned"
    assert deal["submitted"] == "2026-11-21"
    assert deal["expiry"] == "29 days left (2026-12-21)"
    assert deal["payment"] == "Not started"
    assert surface_id(12) == "deal_12_overview"


@pytest.mark.parametrize("expires, status, text", [
    ("2026-12-21T05:44:33Z", "STIPULATED", "29 days left (2026-12-21)"),
    ("2026-11-22T11:00:00Z", "STIPULATED", "Expires today (2026-11-22)"),
    ("2026-11-23T13:00:00Z", "STIPULATED", "2 days left (2026-11-23)"),
    ("2026-11-22T13:00:00Z", "STIPULATED", "1 day left (2026-11-22)"),
    ("2026-11-20T00:00:00Z", "EXPIRED", "Expired on 2026-11-20"),
    (None, "FUNDED", "—"),
])
def test_AS_2_1_daysToExpiry(expires, status, text):
    assert expiry_text(expires, status, NOW) == text


@pytest.mark.parametrize("payment, text", [
    ({"state": "NOT_STARTED"}, "Not started"),
    ({"state": "IN_FLIGHT", "amount": 25000}, "Payment in progress"),
    ({"state": "PAID", "reference": "DSB-42", "amount": 25000}, "Paid: DSB-42, $25,000.00"),
    ({"state": "FAILED"}, "Payment failed: the disbursement API refused it"),
    ({"state": "UNKNOWN"}, "Payment outcome unknown: retry the payment to find out"),
])
def test_AS_2_1_paymentStatus(payment, text):
    assert payment_text(payment) == text


def test_AS_2_2_inProgressIsShown():
    busy = deal_model({**OVERVIEW, "inProgress": True, "workflowStep": "VALIDATING"}, VERSIONS, NOW)
    assert busy["inProgress"] and "running" in busy["progressText"]
    assert deal_model(OVERVIEW, VERSIONS, NOW)["progressText"] == ""


def test_AS_2_3_versionHistoryNewestFirstWithSuperseded():
    rows = deal_model(OVERVIEW, list(reversed(VERSIONS)), NOW)["versions"]
    assert [r["version"] for r in rows] == ["v2", "v1"]
    assert rows[0]["note"] == "latest" and rows[1]["note"] == "superseded by v2"


def test_AS_2_5_temporalLink():
    deal = deal_model(OVERVIEW, VERSIONS, NOW)
    assert deal["temporalUrl"] == "http://localhost:8233/namespaces/default/workflows/funding-local-12"
    assert deal_model({**OVERVIEW, "workflowId": None}, VERSIONS, NOW)["temporalUrl"] == ""


def test_AS_2_1_theFactsAreALabelValueListFromTheDealsOwnFields():
    """D1c: the facts are one FactList bound to /deal/facts, built from the same fields as before."""
    deal = deal_model(OVERVIEW, VERSIONS, NOW)
    assert [f["label"] for f in deal["facts"]] == ["Assigned to", "Expiry", "Payment", "Dealer", "Borrower",
                                                   "Co-borrower", "Stated income", "VIN", "Routed to"]
    facts = {f["label"]: f["value"] for f in deal["facts"]}
    assert facts["Dealer"] == "Lone Star Motors" and facts["Stated income"] == "$85,000.00"
    assert facts["Assigned to"] == "Unassigned" and facts["Co-borrower"] == "None"
    components = overview_surface(deal)[1]["updateComponents"]["components"]
    assert next(c for c in components if c["id"] == "facts") == \
        {"id": "facts", "component": "FactList", "items": {"@path": "/deal/facts"}}
    assert next(c for c in components if c["id"] == "title")["component"] == "Heading"


# ---------------------------------------------------------------- spec 002 T205: the rules on the overview

def refused_take(reason="NOT_YOUR_SPECIALTY"):
    return OVERVIEW | {"allowedActions": [{"action": "TAKE", "allowed": False, "reason": reason}]}


def test_FR_05b_takeIsOfferedOnlyWhenTheApiAllowsIt():
    assert deal_model(OVERVIEW, VERSIONS, NOW)["canTake"] is True
    assert deal_model(refused_take(), VERSIONS, NOW)["canTake"] is False
    ids = {c["id"] for m in overview_surface(deal_model(refused_take(), VERSIONS, NOW))
           if "updateComponents" in m for c in m["updateComponents"]["components"]}
    assert "take_button" not in ids and "summarize_button" in ids          # Summarize is read-only: always there


def test_FR_05b_takeStillNeedsTheDealToBeUnassignedAndOpen():
    assert deal_model(OVERVIEW | {"assignedFunder": "supervisor-1"}, VERSIONS, NOW)["canTake"] is False
    assert deal_model(OVERVIEW | {"status": "FUNDED"}, VERSIONS, NOW)["canTake"] is False


def test_FR_05b_withoutAllowedActionsTheOldRuleStands():
    old = {k: v for k, v in OVERVIEW.items() if k not in ("allowedActions", "routedTo", "workKinds", "viewer")}
    deal = deal_model(old, VERSIONS, NOW)
    assert deal["canTake"] is True and deal["routedTo"] == "—"


@pytest.mark.parametrize("fields, text", [
    ({}, "Documents specialist · documents work (you)"),
    ({"viewer": {"id": "gap-1", "role": "GAP_SPECIALIST"}}, "Documents specialist · documents work"),
    ({"workKinds": ["RISC", "DOCUMENTS", "INCOME"], "routedTo": "INCOME_SPECIALIST"},
     "Income specialist · RISC, documents and income work"),
    ({"workKinds": ["GENERAL"], "routedTo": "GENERAL_FUNDER"}, "Catch-all funder · general work"),
    ({"workKinds": [], "routedTo": None}, "Nobody: nothing needs a specialist"),
])
def test_AS_11_2_routedToSaysWhoAndWhy(fields, text):
    deal = deal_model(OVERVIEW | fields, VERSIONS, NOW)
    assert deal["routedTo"] == text
    assert {f["label"]: f["value"] for f in deal["facts"]}["Routed to"] == text
