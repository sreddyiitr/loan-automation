"""Routing in code (FR-02, FR-03): a text request opens a deal, an action runs its handler."""
from conftest import FakeFunderApi, OVERVIEW, token_for, validate
from funder_agent.server import SIGN_IN, route
from funder_agent.api import message_for


def _data(messages, sid):
    return next(m["updateDataModel"]["value"] for m in messages
                if "updateDataModel" in m and m["updateDataModel"]["surfaceId"] == sid)


def test_AS_1_5_openDealByLoanId(fake):
    messages = route(token_for("funder-1"), "open LA-1006", [], fake.factory())
    validate(messages)
    assert messages[0]["createSurface"]["surfaceId"] == "deal_12_overview"
    assert _data(messages, "deal_12_overview")["dealershipName"] == "Lone Star Motors"


def test_AS_1_5_unknownLoanShowsNotFoundNotAnError(fake):
    messages = route(token_for("funder-1"), "open LA-404", [], fake.factory())
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


def test_AS_10_3_takeAssignsToTheSignedInUserThenRereads(fake):
    action = {"name": "deal.take", "surfaceId": "deal_12_overview",
              "context": {"loanApplicationId": "LA-1006", "applicationVersionId": 12}}
    messages = route(token_for("funder-2"), "", [action], fake.factory())
    put = next(r for r in fake.requests if r.method == "PUT")
    assert put.url.path == "/api/v1/versions/12/assignee" and b'"funder-2"' in put.content
    assert [list(m) for m in messages] == [["version", "updateDataModel"]]     # data only; the layout stays
    assert _data(messages, "deal_12_overview")["assignedFunder"] == "funder-2"


def test_unknownActionIsIgnored(fake):
    assert route(token_for("funder-1"), "", [{"name": "deal.nope", "context": {}}], fake.factory()) == []
