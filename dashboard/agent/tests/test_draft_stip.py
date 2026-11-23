"""D3b (AS-8.3): draft stip text. Checked JSON with one retry; no model means a plain message."""
import asyncio

from conftest import FakeFunderApi, token_for, validate
from funder_agent.actions.assistant import DRAFT_WORDS, draft_stip_text
from funder_agent.assistant.draft_stip import draft
from funder_agent.assistant.summary import ModelUnavailable


class Scripted:
    def __init__(self, *replies):
        self.replies, self.sent = list(replies), []

    async def send(self, text):
        self.sent.append(text)
        if not self.replies:
            raise ModelUnavailable("no model")
        return self.replies.pop(0)


def test_AS_8_3_anInvalidReplyIsRetriedOnceThenTheDraftIsUsed():
    convo = Scripted("Sure! Here it is", '```json\n{"description": "Send a signed letter confirming the payee account."}\n```')
    answer = asyncio.run(draft(convo, {"deal": {"loanApplicationId": "LA-1030"}}))
    assert (answer.kind, answer.attempts) == ("ok", 2)
    assert answer.summary == "Send a signed letter confirming the payee account."
    assert "invalid" in convo.sent[1]


class Down:
    async def available(self):
        return False


def test_AS_8_4_withNoModelTheFormSaysSoAndNothingIsDrafted():
    async def steps():
        with FakeFunderApi().factory()(token_for("docs-1")) as api:
            return [s async for s in draft_stip_text(api, {"loanApplicationId": "LA-1006", "applicationVersionId": 12,
                                                          "code": "STIP_OTHER"}, Down())]
    (first, final), (last, is_final) = steps_ = asyncio.run(steps())
    value = last[0]["updateDataModel"]["value"]
    assert first[0]["updateDataModel"]["value"]["state"] == "working" and is_final
    assert value == {"code": "STIP_OTHER", "state": "unavailable", "text": "", "message": DRAFT_WORDS["unavailable"]}
