"""The funder API client relays the user's token (FR-11) and maps refusals to messages (FR-06)."""
import logging

import httpx
import pytest

from conftest import FakeFunderApi, token_for
from funder_agent.api import ApiError, FunderApi, NotSignedIn, message_for, user_id
from funder_agent.api.messages import messages


def test_FR_11_relaysTheUsersOwnToken(fake):
    token = token_for("funder-1")
    with fake.factory()(token) as api:
        api.overview("LA-1006")
    assert fake.requests[0].headers["Authorization"] == f"Bearer {token}"


def test_FR_11_noTokenMeansNotSignedIn():
    with pytest.raises(NotSignedIn):
        FunderApi(None)


def test_userIdIsTheTokensSub():
    assert user_id(token_for("income-1")) == "income-1"
    with pytest.raises(NotSignedIn):
        user_id("not-a-jwt")


def test_refusalBecomesApiErrorWithItsReason():
    fake = FakeFunderApi(refuse=(409, "VERSION_EXPIRED"))
    with fake.factory()(token_for("funder-1")) as api, pytest.raises(ApiError) as e:
        api.assign(12, "funder-1")
    assert (e.value.status, e.value.reason) == (409, "VERSION_EXPIRED")


def test_nonJsonErrorStillHasAReason():
    api = FunderApi(token_for("funder-1"), base_url="http://funder.test",
                    transport=httpx.MockTransport(lambda r: httpx.Response(502, text="bad gateway")))
    with pytest.raises(ApiError) as e:
        api.overview("LA-1006")
    assert e.value.reason == "HTTP_502"


def test_FR_06_knownReasonsHaveMessages():
    for reason in ["UNAUTHORIZED", "UNKNOWN_USER", "NOT_FOUND", "VERSION_EXPIRED", "DISBURSEMENT_IN_PROGRESS"]:
        assert reason in messages() and message_for(reason).strip()


def test_FR_06_unknownReasonGetsFallbackAndIsLogged(caplog):
    with caplog.at_level(logging.WARNING, logger="funder-agent"):
        text = message_for("SOMETHING_NEW")
    assert "SOMETHING_NEW" in text
    assert "no message for funder API reason SOMETHING_NEW" in caplog.text
