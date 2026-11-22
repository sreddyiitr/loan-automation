"""My work (spec 002 AS-1.8; D6a, T613): what's assigned to you and what's ready for you to take."""
from __future__ import annotations

from datetime import datetime, timezone

import pytest

from conftest import FakeFunderApi, token_for, validate
from funder_agent.server import route
from funder_agent.surfaces.worklist import OPEN_ACTION, SURFACE_ID, missing, work_model, worklist_surface

NOW = datetime(2026, 11, 21, 12, 0, tzinfo=timezone.utc)


def row(loan="LA-1006", **over):
    base = {"loanApplicationId": loan, "applicationVersionId": 12, "versionNumber": 1, "status": "STIPULATED",
            "dealershipName": "Lone Star Motors", "assignedFunder": None, "expiresAt": "2026-12-21T05:44:33Z",
            "workKinds": ["DOCUMENTS"], "routedTo": "DOCUMENTS_SPECIALIST",
            "pendingStips": [{"code": "STIP_PRICE_MISMATCH", "check": "PRICE"}], "checksNotPassed": [],
            "riscIncomplete": False, "payment": "NOT_STARTED"}
    return base | over


@pytest.mark.parametrize("fields, words", [
    ({}, ["Price differs between the RISC and the Buyer's Order"]),
    ({"pendingStips": [{"code": "STIP_TILA_MATH_DISCREPANCY", "check": "TILA"},
                       {"code": "STIP_VIN_MISMATCH", "check": "VIN"}]},
     ["TILA math doesn't add up", "VIN or vehicle details differ between documents"]),
    ({"pendingStips": [{"code": "STIP_EXTRACTION_REVIEW_REQUIRED", "check": "PRICE"}]}, ["A field needs review (Price check)"]),
    ({"pendingStips": [{"code": "STIP_DEALER_LETTER", "check": None}]}, ["Dealer letter"]),          # a manual stip's own code
    ({"pendingStips": [], "checksNotPassed": [{"check": "INCOME", "outcome": "ERROR"}]}, ["Income check couldn't finish"]),
    ({"pendingStips": [], "checksNotPassed": [{"check": "INCOME", "outcome": "NEEDS_REVIEW"}]}, ["Income needs review"]),
    ({"pendingStips": [], "riscIncomplete": True}, ["RISC incomplete"]),
    ({"pendingStips": [], "status": "APPROVED", "payment": "UNKNOWN"}, ["Payment outcome unknown"]),
    ({"pendingStips": [], "status": "APPROVED", "payment": "FAILED"}, ["Payment failed"]),
])
def test_AS_1_8_whatsMissingIsInPlainWords(fields, words):
    assert missing(row(**fields)) == words


def test_AS_1_8_aDealWithNothingMissingSaysItsWaitingOnTheSystem():
    model = work_model([row(pendingStips=[], status="APPROVED", payment="IN_FLIGHT")], [], NOW)
    assert model["mine"][0]["missing"] == ["Nothing missing: waiting on the system"]


def test_AS_1_8_eachRowHasStatusDealerExpiryAndWhoItsFor():
    r = work_model([row()], [], NOW)["mine"][0]
    assert r == {"loanApplicationId": "LA-1006", "versionNumber": 1, "dealer": "Lone Star Motors", "status": "STIPULATED",
                 "missing": ["Price differs between the RISC and the Buyer's Order"],
                 "routedTo": "Documents specialist", "expiry": "29 days left (2026-12-21)"}


def test_AS_1_8_theSurfaceIsTwoListsOfClickableDealsAndPassesStrictValidation():
    messages = worklist_surface(work_model([row()], [row("LA-1007")], NOW))
    validate(messages)
    components = {c["id"]: c for m in messages if "updateComponents" in m for c in m["updateComponents"]["components"]}
    assert components["mine"]["title"] == "Assigned to you" and components["ready"]["title"] == "Ready for you to take"
    assert {components[k]["openAction"] for k in ("mine", "ready")} == {OPEN_ACTION}
    assert messages[0] == {"version": "1.0", "deleteSurface": {"surfaceId": SURFACE_ID}}     # rebuilt: always fresh
    validate(messages, messages)                                                             # and can follow itself


def test_AS_1_8_myWorkReadsBothQueuesWithTheUsersToken():
    fake = FakeFunderApi()
    fake.queues = {"mine": [row()], "ready": [row("LA-1007")]}
    messages = route(token_for("docs-1"), "my work", [], fake.factory())
    data = next(m["updateDataModel"]["value"] for m in messages if "updateDataModel" in m)
    assert [r["loanApplicationId"] for r in data["mine"]] == ["LA-1006"]
    assert [r["loanApplicationId"] for r in data["ready"]] == ["LA-1007"]
    queues = sorted(r.url.params["queue"] for r in fake.requests if r.url.path.endswith("/applications"))
    assert queues == ["mine", "ready"]
    assert all(r.headers["Authorization"].startswith("Bearer ") for r in fake.requests)


def test_AS_1_8_signingInWithNoTextLandsOnMyWorkToo():
    fake = FakeFunderApi()
    assert route(token_for("docs-1"), "", [], fake.factory())[1]["createSurface"]["surfaceId"] == SURFACE_ID


def test_AS_1_5_clickingADealOpensItsOverview():
    fake = FakeFunderApi()
    messages = route(token_for("docs-1"), "", [{"name": OPEN_ACTION, "context": {"loanApplicationId": "LA-1006"}}], fake.factory())
    validate(messages)
    assert messages[0]["createSurface"]["surfaceId"] == "deal_12_overview"
