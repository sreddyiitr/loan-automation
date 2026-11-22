"""Routing in code (FR-02, FR-03): a text request opens a deal, an action runs its handler."""
from conftest import FakeFunderApi, OVERVIEW, token_for, validate
from funder_agent.server import SIGN_IN, route
from funder_agent.api import message_for


def _data(messages, sid):
    return next(m["updateDataModel"]["value"] for m in messages
                if "updateDataModel" in m and m["updateDataModel"]["surfaceId"] == sid)


def test_AS_1_5_openDealByLoanId(fake):
    messages = route(token_for("docs-1"), "open LA-1006", [], fake.factory())
    validate(messages)
    assert messages[0]["createSurface"]["surfaceId"] == "deal_12_overview"
    assert _data(messages, "deal_12_overview")["dealershipName"] == "Lone Star Motors"


def test_AS_1_5_unknownLoanShowsNotFoundNotAnError(fake):
    messages = route(token_for("docs-1"), "open LA-404", [], fake.factory())
    validate(messages)
    assert _data(messages, "notice")["message"] == message_for("NOT_FOUND")


def test_FR_11_withoutATokenTheAgentAsksToSignIn(fake):
    messages = route(None, "open LA-1006", [], fake.factory())
    assert _data(messages, "notice")["message"] == SIGN_IN
    assert fake.requests == []                       # nothing reached the funder API


def test_FR_06_refusalBecomesAPlainMessage():
    fake = FakeFunderApi(refuse=(403, "UNKNOWN_USER"))
    messages = route(token_for("stranger"), "open LA-1006", [], fake.factory())
    assert _data(messages, "notice")["message"] == message_for("UNKNOWN_USER")


TAKE = {"name": "deal.take", "surfaceId": "deal_12_overview",
        "context": {"loanApplicationId": "LA-1006", "applicationVersionId": 12}}


def _ids(messages, sid="deal_12_overview"):
    return {c["id"] for m in messages if "updateComponents" in m and m["updateComponents"]["surfaceId"] == sid
            for c in m["updateComponents"]["components"]}


def test_AS_2_6_takeThenTheOverviewIsRebuiltWithoutTake(fake):
    token = token_for("supervisor-1")
    first = route(token, "open LA-1006", [], fake.factory())
    assert "take_modal" in _ids(first)                                   # unassigned: Take is offered
    reply = route(token, "", [TAKE], fake.factory())
    post = next(r for r in fake.requests if r.method == "POST")
    assert post.url.path == "/api/v1/versions/12/take"
    assert list(reply[0]) == ["version", "deleteSurface"] and "createSurface" in reply[1]   # rebuilt (closes the Modal)
    assert _data(reply, "deal_12_overview")["assignedFunder"] == "supervisor-1 (you)"
    assert "take_modal" not in _ids(reply)                               # taken: no more Take
    validate(first, reply)                                               # as the browser applies them, in order


def test_AS_2_6_someoneElseTookItFirst():
    fake = FakeFunderApi(overview={**OVERVIEW, "assignedFunder": "funder-9"})   # a stale screen still offered Take
    reply = route(token_for("docs-1"), "", [TAKE], fake.factory())
    assert _data(reply, "deal_12_overview")["assignedFunder"] == "funder-9"
    assert _data(reply, "notice")["message"] == message_for("ALREADY_ASSIGNED")
    validate(reply)


def test_uiOnlyEventsAreIgnoredQuietly(fake, caplog):
    assert route(token_for("docs-1"), "", [{"name": "ui.confirm_take", "context": {}}], fake.factory()) == []
    assert "no handler" not in caplog.text and fake.requests == []


def test_unknownActionIsIgnored(fake):
    assert route(token_for("docs-1"), "", [{"name": "deal.nope", "context": {}}], fake.factory()) == []
