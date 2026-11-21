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


def test_NFR_05_noticeSurfaceIsValidA2ui():
    validate(notice_surface("Sign in to use the funder dashboard."))


@pytest.mark.parametrize("break_it", [
    lambda c: c.__setitem__("variant", "h2"),            # Text allows only caption | body (D0 pitfall)
    lambda c: c.__setitem__("component", "Nope"),        # not in our catalog
])
def test_NFR_05_theValidatorReallyRejects(break_it):
    messages = overview_surface(deal_model(OVERVIEW, VERSIONS, NOW))
    bad = copy.deepcopy(messages)
    title = next(c for c in bad[1]["updateComponents"]["components"] if c["id"] == "title")
    break_it(title)
    with pytest.raises(A2uiValidationError):
        validate(bad)


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
