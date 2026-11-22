"""Summarize (spec 002 AS-8.1, AS-8.4, AS-8.5, FR-08; T153). No model runs here: a scripted
conversation stands in for it, and one ADK test uses a scripted ADK model."""
from __future__ import annotations

import asyncio
import json
from typing import Any

import pytest
from google.adk.models.base_llm import BaseLlm
from google.adk.models.llm_response import LlmResponse
from google.genai import types
from pydantic import Field

from funder_agent.assistant.summary import (INSTRUCTION, MAX_CHARS, AdkConversation, InvalidAnswer, ModelUnavailable,
                                            parse, prompt, summarize, summary_input)
from tests.conftest import OVERVIEW

STIPS = [
    {"stipId": 20, "validationCheck": "PRICE", "stipCode": "STIP_PRICE_MISMATCH", "status": "PENDING",
     "description": "Cash price mismatch: RISC 31450.00, Buyer's Order 31450.01", "resolutionNote": None,
     "resolvedBy": None, "triggeredByDocumentId": 17},
    {"stipId": 21, "validationCheck": "VIN", "stipCode": "STIP_VIN_MISMATCH", "status": "WAIVED",
     "description": "VIN differs on GAP", "resolutionNote": "typo on the GAP form", "resolvedBy": "supervisor-1"},
]
RESULTS = [
    {"resultId": 1, "validationCheck": "PRICE", "outcome": "FAIL", "attempt": 1, "isCurrent": True,
     "details": {"comparisons": [
         {"ok": False, "name": "cash price RISC = Buyer's Order", "expected": "31450.00", "actual": "31450.01"},
         {"ok": True, "name": "cash price present", "expected": "present", "actual": "present"}],
         "document_ids": {"RISC": 16, "BUYERS_ORDER": 17}, "flagged_fields": []}},
    {"resultId": 2, "validationCheck": "INCOME", "outcome": "ERROR", "attempt": 2, "isCurrent": True,
     "details": {"error": "income API: server error (503)", "comparisons": [], "document_ids": {}, "flagged_fields": []}},
    {"resultId": 3, "validationCheck": "INCOME", "outcome": "FAIL", "attempt": 1, "isCurrent": False,
     "details": {"comparisons": [{"ok": False, "name": "old attempt", "expected": "x", "actual": "y"}]}},
    {"resultId": 4, "validationCheck": "TILA", "outcome": "PASS", "attempt": 1, "isCurrent": True,
     "details": {"comparisons": [{"ok": True, "name": "amount financed", "expected": "1", "actual": "1"}]}},
]
GOOD = '```json\n{"summary": "LA-1006 is stipulated because the cash price differs by $0.01."}\n```'


class Scripted:
    """A conversation that replies from a script and records what it was sent."""

    def __init__(self, *replies: Any):
        self.replies, self.sent = list(replies), []

    async def send(self, text: str) -> str:
        self.sent.append(text)
        reply = self.replies.pop(0)
        if isinstance(reply, Exception):
            raise reply
        return reply


def run(conversation, data=None):
    return asyncio.run(summarize(conversation, data or summary_input(OVERVIEW, STIPS, RESULTS)))


# ---------------------------------------------------------------- AS-8.5: what the model sees

def test_AS_8_5_theModelSeesOnlyThisDealTrimmedToWhatExplainsIt():
    data = summary_input(OVERVIEW, STIPS, RESULTS)
    assert data["deal"]["loanApplicationId"] == "LA-1006"
    assert data["checksPassed"] == ["TILA"]                                   # named, not detailed
    assert [r["check"] for r in data["checksNotPassed"]] == ["PRICE", "INCOME"]   # current attempts only
    assert data["checksNotPassed"][0]["failing"] == [
        {"name": "cash price RISC = Buyer's Order", "expected": "31450.00", "actual": "31450.01"}]   # failing ones only
    assert data["checksNotPassed"][1]["error"] == "income API: server error (503)"
    assert [s["code"] for s in data["openStips"]] == ["STIP_PRICE_MISMATCH"]
    assert data["resolvedStips"] == [{"code": "STIP_VIN_MISMATCH", "check": "VIN", "description": "VIN differs on GAP",
                                      "status": "WAIVED", "resolvedBy": "supervisor-1", "note": "typo on the GAP form"}]


def test_AS_8_5_noNamesIdsOrSsnReachTheModel():
    text = prompt(summary_input(OVERVIEW, STIPS, RESULTS))
    for absent in ("Maria Lopez", "1HGCM82633A004352", "document_ids", "resultId", "stipId", "workflowId",
                   "applicationVersionId", "ssn"):
        assert absent.lower() not in text.lower(), absent
    assert "LA-" in text and text.count("LA-") == 1                           # this deal's id, once, and no other


def test_AS_8_1_aFinishedDealIsSummarizedToo():
    funded = OVERVIEW | {"status": "FUNDED", "expiresAt": None,
                         "payment": {"state": "PAID", "reference": "DISB-77", "amount": 31450.0}}
    deal = summary_input(funded, [], [])["deal"]
    assert deal["status"] == "FUNDED"
    assert deal["payment"] == "Paid: DISB-77, $31,450.00"
    assert "how it ended" in INSTRUCTION


# ---------------------------------------------------------------- FR-08: checking the answer

@pytest.mark.parametrize("reply", [
    GOOD,
    '{"summary": "LA-1006 is stipulated because the cash price differs by $0.01."}',
    '```\n{"summary": "LA-1006 is stipulated because the cash price differs by $0.01."}\n```',
    '  {"summary": "LA-1006 is stipulated   because the cash price\\ndiffers by $0.01."}  ',
])
def test_FR_08_aValidReplyWithOrWithoutAFenceIsAccepted(reply):
    assert parse(reply) == "LA-1006 is stipulated because the cash price differs by $0.01."


@pytest.mark.parametrize("reply, error", [
    ("The deal is stipulated.", "wasn't valid JSON"),
    ('```json\n{"summary": "x"\n```', "wasn't valid JSON"),
    ('["a summary"]', 'exactly one key, "summary"'),
    ('{"summary": "x", "suggestions": ["waive it"]}', 'exactly one key, "summary"'),   # read-only: no actions
    ('{"text": "x"}', 'exactly one key, "summary"'),
    ('{"summary": "   "}', "non-empty string"),
    ('{"summary": 42}', "non-empty string"),
    (json.dumps({"summary": "a" * (MAX_CHARS + 1)}), f"at most {MAX_CHARS} characters"),
])
def test_FR_08_anInvalidReplyIsRefusedWithAReason(reply, error):
    with pytest.raises(InvalidAnswer, match=error):
        parse(reply)


def test_FR_08_aValidFirstReplyIsUsed():
    convo = Scripted(GOOD)
    answer = run(convo)
    assert (answer.kind, answer.attempts) == ("ok", 1)
    assert answer.summary.startswith("LA-1006 is stipulated")
    assert convo.sent[0].startswith('Reply with only a JSON object') and "DEAL DATA (JSON):" in convo.sent[0]


def test_FR_08_anInvalidReplyIsRetriedOnceWithTheError():
    convo = Scripted("Sure! The deal is stipulated.", GOOD)
    answer = run(convo)
    assert (answer.kind, answer.attempts) == ("ok", 2)
    assert convo.sent[1].startswith("That reply was invalid: the reply wasn't valid JSON")
    assert "DEAL DATA" not in convo.sent[1]              # the same conversation: the data isn't sent twice


def test_AS_8_4_invalidTwiceIsCouldntProduceAnAnswer():
    answer = run(Scripted("nope", '{"summary": ""}'))
    assert (answer.kind, answer.attempts, answer.summary) == ("invalid", 2, "")
    assert "non-empty string" in answer.error


def test_AS_8_4_aModelThatCantBeReachedIsUnavailableWithNoRetry():
    convo = Scripted(ModelUnavailable("APIConnectionError: connection refused"), GOOD)
    answer = run(convo)
    assert (answer.kind, answer.attempts) == ("unavailable", 1)
    assert len(convo.sent) == 1


# ---------------------------------------------------------------- the ADK plumbing (D-14)

class ScriptedLlm(BaseLlm):
    """An ADK model that replies from a script, so the real LlmAgent and runner run with no model."""
    replies: list[Any] = Field(default_factory=list)
    requests: list[Any] = Field(default_factory=list)

    async def generate_content_async(self, llm_request, stream: bool = False):
        self.requests.append(llm_request)
        reply = self.replies.pop(0)
        if isinstance(reply, Exception):
            raise reply
        yield LlmResponse(content=types.Content(role="model", parts=reply))


def text(t: str, thought: bool = False) -> types.Part:
    return types.Part(text=t, thought=thought or None)


def test_D_14_theSummaryRunsAsAnAdkAgentAndTheRetrySeesTheFirstReply():
    llm = ScriptedLlm(model="scripted", replies=[[text("not json")], [text("thinking...", thought=True), text(GOOD)]])
    answer = asyncio.run(summarize(AdkConversation(llm), summary_input(OVERVIEW, STIPS, RESULTS)))
    assert (answer.kind, answer.attempts) == ("ok", 2)
    first, retry = llm.requests
    assert INSTRUCTION.splitlines()[0] in str(first.config.system_instruction)
    roles = [c.role for c in retry.contents]
    assert roles == ["user", "model", "user"]             # one session: the retry carries the first exchange
    assert "DEAL DATA" in retry.contents[0].parts[0].text


def test_D_14_hiddenThinkingIsNotPartOfTheReply():
    llm = ScriptedLlm(model="scripted", replies=[[text('{"summary": "ignore me"}', thought=True), text(GOOD)]])
    answer = asyncio.run(summarize(AdkConversation(llm), summary_input(OVERVIEW, STIPS, RESULTS)))
    assert answer.summary.startswith("LA-1006 is stipulated")


def test_AS_8_4_aModelErrorThroughAdkIsUnavailable():
    llm = ScriptedLlm(model="scripted", replies=[ConnectionError("connection refused")])
    answer = asyncio.run(summarize(AdkConversation(llm), summary_input(OVERVIEW, STIPS, RESULTS)))
    assert answer.kind == "unavailable" and "connection refused" in answer.error


def test_AS_8_5_eachRequestGetsItsOwnSession():
    llm = ScriptedLlm(model="scripted", replies=[[text(GOOD)], [text(GOOD)]])
    for _ in range(2):
        asyncio.run(summarize(AdkConversation(llm), summary_input(OVERVIEW, STIPS, RESULTS)))
    assert [len(r.contents) for r in llm.requests] == [1, 1]   # the second request saw nothing of the first
