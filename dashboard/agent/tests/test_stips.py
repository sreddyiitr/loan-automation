"""D3 (T302): the stips surface. Satisfy and waive with a reason, Waive hidden when not waivable,
add from the code list, and every refusal a plain message (AS-3.1–3.5, AS-6.4)."""
from conftest import OVERVIEW, FakeFunderApi, token_for, validate
from funder_agent.api import message_for
from funder_agent.server import route
from funder_agent.surfaces.stips import NOT_WAIVABLE, stips_model

SID = "deal_12_overview"
CTX = {"loanApplicationId": "LA-1006", "applicationVersionId": 12}
PRICE = {"stipId": 1, "validationCheck": "PRICE", "stipCode": "STIP_PRICE_MISMATCH", "description": "Price differs",
         "status": "PENDING", "waivable": True, "triggeredByDocumentType": "BUYERS_ORDER"}
TILA = {"stipId": 2, "validationCheck": "TILA", "stipCode": "STIP_TILA_MATH_DISCREPANCY", "description": "TILA off",
        "status": "PENDING", "waivable": False}


def _deal(messages):
    return next(m["updateDataModel"]["value"] for m in messages
                if "updateDataModel" in m and m["updateDataModel"]["surfaceId"] == SID)


def _notice(messages):
    return next(m["updateDataModel"]["value"]["message"] for m in messages
                if "updateDataModel" in m and m["updateDataModel"]["surfaceId"] == "notice")


def _fake(*stips):
    fake = FakeFunderApi()
    fake.stips = [dict(s) for s in stips]
    return fake


def test_AS_3_1_eachStipSaysWhatRaisedItAndWhoResolvedIt():
    system = {**PRICE, "status": "SATISFIED", "resolvedBy": "system", "resolutionNote": "auto-resolved by re-run",
              "resolvedAt": "2026-11-20T10:00:00Z"}
    waived = {**PRICE, "stipId": 3, "status": "WAIVED", "resolvedBy": "price-1", "resolutionNote": "dealer letter",
              "resolvedAt": "2026-11-21T10:00:00Z"}
    rows = stips_model(OVERVIEW, [system, waived], [])["stips"]
    assert rows[0]["raisedBy"] == "Price check · BUYERS ORDER"
    assert rows[0]["resolved"].startswith("Satisfied by the system")
    assert rows[1]["resolved"] == "Waived by price-1 · 2026-11-21 · “dealer letter”"
    assert not rows[0]["canSatisfy"] and not rows[1]["canWaive"]          # resolved: nothing to do


def test_AS_3_2_waiveWithAReasonThenTheDealIsReread():
    fake = _fake(PRICE)
    reply = route(token_for("price-1"), "", [{"name": "deal.resolve_stip", "surfaceId": SID, "context": {
        **CTX, "stipId": 1, "status": "WAIVED", "reason": " dealer letter "}}], fake.factory())
    patch = next(r for r in fake.requests if r.method == "PATCH")
    assert patch.url.path == "/api/v1/versions/12/stipulations/1"
    assert patch.content == b'{"status":"WAIVED","reason":"dealer letter"}'
    assert _deal(reply)["stips"][0]["status"] == "WAIVED"
    assert list(reply[0]) == ["version", "deleteSurface"]                  # rebuilt, so the status refreshes too
    validate(reply)


def test_AS_3_3_waiveIsNotOfferedForAStipThatCantBeWaived():
    mine = {**OVERVIEW, "allowedActions": [{"action": "RESOLVE_STIP", "allowed": True}]}
    row = stips_model(mine, [TILA], [])["stips"][0]
    assert row["canSatisfy"] and not row["canWaive"] and row["note"] == NOT_WAIVABLE


def test_AS_3_4_someoneElseResolvedItFirst():
    fake = _fake({**PRICE, "status": "SATISFIED", "resolvedBy": "price-2"})      # a stale screen still offered Satisfy
    reply = route(token_for("price-1"), "", [{"name": "deal.resolve_stip", "surfaceId": SID, "context": {
        **CTX, "stipId": 1, "status": "SATISFIED", "reason": "ok"}}], fake.factory())
    assert _notice(reply) == message_for("ALREADY_RESOLVED")
    assert "price-2" in _deal(reply)["stips"][0]["resolved"]
    validate(reply)


def test_AS_3_5_addStipFromTheCodeList():
    fake = _fake()
    first = route(token_for("docs-1"), "open LA-1006", [], fake.factory())
    assert [c["value"] for c in _deal(first)["stipCodes"]] == ["STIP_PRICE_MISMATCH", "STIP_TILA_MATH_DISCREPANCY", "STIP_OTHER"]
    reply = route(token_for("docs-1"), "", [{"name": "deal.add_stip", "surfaceId": SID, "context": {
        **CTX, "code": "STIP_OTHER", "description": "Call the dealer"}}], fake.factory())
    assert _deal(reply)["stips"][0]["status"] == "PENDING"
    validate(first, reply)


def test_AS_6_4_anUnknownCodeIsAPlainMessage():
    reply = route(token_for("docs-1"), "", [{"name": "deal.add_stip", "surfaceId": SID, "context": {
        **CTX, "code": "STIP_MADE_UP", "description": "x"}}], _fake().factory())
    assert _notice(reply) == message_for("UNKNOWN_STIP_CODE")


def test_AS_3_5_addStipDisabledWithTheApiReason():
    overview = {**OVERVIEW, "allowedActions": [{"action": "ADD_STIP", "allowed": False, "reason": "NOT_ASSIGNED_TO_YOU"},
                                               {"action": "RESOLVE_STIP", "allowed": False, "reason": "NOT_ASSIGNED_TO_YOU"}]}
    model = stips_model(overview, [PRICE], [])
    assert not model["canAddStip"] and model["addStipReason"] == message_for("NOT_ASSIGNED_TO_YOU")
    assert not model["stips"][0]["canSatisfy"] and not model["stips"][0]["canWaive"]
