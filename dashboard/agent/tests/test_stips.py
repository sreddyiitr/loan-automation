"""D3 (T302): the stips surface. Satisfy and waive with a reason, Waive hidden when not waivable,
add from the code list, and every refusal a plain message (AS-3.1–3.5, AS-6.4)."""
import asyncio
import json

from starlette.testclient import TestClient

from conftest import OVERVIEW, FakeFunderApi, token_for, validate
from funder_agent import config
from funder_agent.actions.deal import settle
from funder_agent.api import message_for
from funder_agent.server import build_app, route
from test_assistant_panel import HEADERS, a2a_request, a2ui
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
    assert not row["canWaive"] and row["note"] == NOT_WAIVABLE
    assert not row["canSatisfy"]                  # the API refuses it too (spec 001 AS-8.10): not offered either
    assert stips_model(mine, [PRICE], [])["stips"][0]["canSatisfy"]       # the same viewer can satisfy a waivable one


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


class Settling(FakeFunderApi):
    """A deal the workflow is still acting on: each overview read moves it one step along ``steps``."""

    def __init__(self, *steps):
        super().__init__({**OVERVIEW, **steps[0]})
        self.steps, self.reads = list(steps), 0

    def handler(self, request):
        if request.method == "GET" and request.url.path == "/api/v1/applications/LA-1006":
            self.overview |= self.steps[min(self.reads, len(self.steps) - 1)]
            self.reads += 1
        return super().handler(request)


APPLYING = {"status": "STIPULATED", "workflowStep": "APPLYING", "inProgress": True}
FUNDED = {"status": "FUNDED", "workflowStep": "DONE", "inProgress": False, "payment": {"state": "PAID"}}


def _settle(fake, **kw):
    async def collect():
        with fake.factory()(token_for("docs-1")) as api:
            return [u async for u in settle(api, CTX, interval=0, **kw)]
    return asyncio.run(collect())


def test_FR_10_afterAWaiveTheDealIsRereadUntilItFunds():
    updates = _settle(Settling(APPLYING, APPLYING, FUNDED))
    assert [_deal(u)["status"] for u in updates] == ["STIPULATED", "FUNDED"]   # an unchanged re-read isn't sent
    assert all(list(m) == ["version", "updateDataModel"] for u in updates for m in u)   # data only: the layout stays


def test_FR_10_theFirstRereadHappensEvenIfTheDealLooksSettled():
    # right after the write, the workflow may not have picked the signal up yet
    updates = _settle(Settling({"status": "STIPULATED", "inProgress": False}))
    assert len(updates) == 1 and not _deal(updates[0])["inProgress"]


def test_FR_10_itStopsFollowingAfterTheLimit():
    fake = Settling(APPLYING, {"workflowStep": "VALIDATING"}, {"workflowStep": "APPLYING"})
    assert len(_settle(fake, limit=0.0)) == 0
    fake = Settling(APPLYING)
    async def collect():
        with fake.factory()(token_for("docs-1")) as api:
            return [u async for u in settle(api, CTX, interval=0.001, limit=0.005)]
    asyncio.run(collect())
    assert fake.reads <= 5                                                     # never more than limit / interval


def test_AS_3_2_overMessageStreamTheWaiveRepliesAtOnceThenTheFundedDeal(monkeypatch):
    monkeypatch.setattr(config, "FRESHNESS_INTERVAL_S", 0)
    fake = Settling(APPLYING, FUNDED)
    fake.stips = [dict(PRICE)]
    action = {"name": "deal.resolve_stip", "surfaceId": SID, "sourceComponentId": "stips", "timestamp": "2026-11-22T10:00:00Z",
              "context": {**CTX, "stipId": 1, "status": "WAIVED", "reason": "dealer letter"}}
    client = TestClient(build_app(fake.factory()))
    events = []
    with client.stream("POST", "/", json=a2a_request("message/stream", action), headers=HEADERS) as r:
        for line in r.iter_lines():
            if line.startswith("data:"):
                events.append(json.loads(line[5:])["result"])
    steps = [(e["status"]["state"], a2ui(e["status"]["message"])) for e in events
             if e.get("kind") == "status-update" and e["status"].get("message")]
    assert [state for state, _ in steps] == ["working", "working", "completed"]
    first = steps[0][1]
    assert list(first[0]) == ["version", "deleteSurface"] and _deal(first)["stips"][0]["status"] == "WAIVED"
    assert _notice(first) == "Stip waived."
    assert _deal(steps[1][1])["status"] == "FUNDED" and _deal(steps[2][1])["status"] == "FUNDED"
    validate(*[messages for _, messages in steps])
