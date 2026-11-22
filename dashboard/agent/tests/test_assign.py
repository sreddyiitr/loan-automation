"""Assignment suggestions for the supervisor (spec 002 US-12; T253-T255). No model runs: scripted
conversations stand in for it, and one test drives a real ADK runner with a scripted model that calls a tool."""
from __future__ import annotations

import asyncio
import json
from datetime import datetime, timezone
from typing import Any

import pytest
from google.adk.models.base_llm import BaseLlm
from google.adk.models.llm_response import LlmResponse
from google.genai import types
from pydantic import Field

from conftest import FakeFunderApi, token_for, validate
from funder_agent.assistant.assign import (INSTRUCTION, InvalidSuggestions, code_plan, deal_facts, keep_eligible, parse,
                                           suggest, tools_for)
from funder_agent.assistant.summary import AdkConversation, ModelUnavailable
from funder_agent.server import route
from funder_agent.surfaces.assign import ASSIGN_ACTION, assign_surface
from funder_agent.surfaces.worklist import work_model, worklist_surface
from test_summary import Scripted

NOW = datetime(2026, 11, 21, 12, 0, tzinfo=timezone.utc)
USERS = [{"id": "docs-1", "role": "DOCUMENTS_SPECIALIST", "openDeals": 2},
         {"id": "docs-2", "role": "DOCUMENTS_SPECIALIST", "openDeals": 0},
         {"id": "income-1", "role": "INCOME_SPECIALIST", "openDeals": 1},
         {"id": "supervisor-1", "role": "SUPERVISOR", "openDeals": 3}]


def ready(loan, routed, days, vid=10, earlier=()):
    return {"loanApplicationId": loan, "applicationVersionId": vid, "dealershipName": "Lone Star Motors",
            "borrowerName": "Jordan Rivera", "workKinds": ["DOCUMENTS"], "routedTo": routed,
            "expiresAt": f"2026-{12 if days > 9 else 11}-{(21 + days) if days <= 9 else days:02d}T12:00:00Z",
            "earlierAssignees": list(earlier)}


READY = [ready("LA-1006", "DOCUMENTS_SPECIALIST", 3, 12), ready("LA-1007", "DOCUMENTS_SPECIALIST", 1, 7),
         ready("LA-1027", "INCOME_SPECIALIST", 5, 27), ready("LA-1099", "GAP_SPECIALIST", 2, 99)]
GOOD = json.dumps({"suggestions": [
    {"loanId": "LA-1007", "assignee": "docs-2", "reason": "Most urgent; docs-2 has no open deals."},
    {"loanId": "LA-1006", "assignee": "docs-2", "reason": "docs-2 is free."},
    {"loanId": "LA-1027", "assignee": "income-1", "reason": "The only income specialist."}]})


def run(conversation, ready_rows=READY):
    return asyncio.run(suggest(conversation, ready_rows, USERS, NOW))


# ---------------------------------------------------------------- what the model sees (AS-12.6)

def test_AS_12_6_theModelSeesIdsKindsUrgencyAndEligibilityButNoNames():
    facts = deal_facts(READY, USERS, NOW)
    assert [f["loanId"] for f in facts] == ["LA-1007", "LA-1099", "LA-1006", "LA-1027"]   # most urgent first
    assert facts[0] == {"loanId": "LA-1007", "kinds": ["DOCUMENTS"], "routedTo": "DOCUMENTS_SPECIALIST",
                        "eligible": ["docs-1", "docs-2"], "daysToExpiry": 1, "earlierAssignees": []}
    text = json.dumps(facts) + json.dumps([t() for t in tools_for(facts, USERS)[:2]])
    for absent in ("Lone Star", "Rivera", "dealership", "borrower"):
        assert absent not in text


def test_AS_12_2_whoIsEligibleIsDecidedByCode():
    facts = {f["loanId"]: f for f in deal_facts(READY, USERS, NOW)}
    assert facts["LA-1027"]["eligible"] == ["income-1"]
    assert facts["LA-1099"]["eligible"] == ["supervisor-1"]       # no GAP specialist on this team: the supervisor


# ---------------------------------------------------------------- code's own plan (AS-12.5)

def test_AS_12_5_codesPlanGivesEachDealToTheLeastBusyEligiblePersonMostUrgentFirst():
    plan = code_plan(deal_facts(READY, USERS, NOW), USERS)
    assert plan["LA-1007"]["assignee"] == "docs-2"                 # 0 open vs docs-1's 2
    assert plan["LA-1006"]["assignee"] == "docs-2"                 # still 1 vs 2 after its first pick
    assert plan["LA-1027"]["assignee"] == "income-1"
    assert plan["LA-1007"]["reason"] == "Fewest open deals among those eligible (0)"


def test_AS_12_3_onATieSomeoneWhoWorkedAnEarlierVersionWins():
    users = [{"id": "docs-1", "role": "DOCUMENTS_SPECIALIST", "openDeals": 1},
             {"id": "docs-2", "role": "DOCUMENTS_SPECIALIST", "openDeals": 1}]
    plan = code_plan(deal_facts([ready("LA-1006", "DOCUMENTS_SPECIALIST", 3, earlier=["docs-2"])], users, NOW), users)
    assert plan["LA-1006"] == {"assignee": "docs-2", "reason": "Fewest open deals among those eligible (1); worked an earlier version"}


# ---------------------------------------------------------------- the model's answer, checked

def test_FR_08_aFencedReplyIsReadAndAMalformedOneRefused():
    assert parse(f"```json\n{GOOD}\n```")[0]["loanId"] == "LA-1007"
    with pytest.raises(InvalidSuggestions, match="valid JSON"):
        parse("I'd give LA-1007 to docs-2")
    with pytest.raises(InvalidSuggestions, match='"suggestions" list'):
        parse('{"assign": []}')


def test_AS_12_2_aSuggestionForSomeoneNotEligibleOrADealNotListedIsDropped():
    facts = deal_facts(READY, USERS, NOW)
    kept, dropped = keep_eligible([{"loanId": "LA-1007", "assignee": "income-1", "reason": "x"},     # wrong specialty
                                   {"loanId": "LA-4040", "assignee": "docs-1", "reason": "x"},       # not a ready deal
                                   {"loanId": "LA-1006", "assignee": "docs-1", "reason": "x"},
                                   {"loanId": "LA-1006", "assignee": "docs-2", "reason": "x"}], facts)   # second one for a deal
    assert kept == [{"loanId": "LA-1006", "assignee": "docs-1", "reason": "x"}]
    assert dropped == ["LA-1007 -> income-1", "LA-4040 -> docs-1", "LA-1006 -> docs-2"]


def test_AS_12_1_theAssistantsSuggestionsAreUsedAndGapsFilledByCodesPlan():
    result = run(Scripted(GOOD))
    assert result.mode == "assistant"
    by = {i["loanApplicationId"]: i for i in result.items}
    assert [i["loanApplicationId"] for i in result.items] == ["LA-1007", "LA-1099", "LA-1006", "LA-1027"]
    assert (by["LA-1007"]["assignee"], by["LA-1007"]["source"]) == ("docs-2", "assistant")
    assert (by["LA-1099"]["assignee"], by["LA-1099"]["source"]) == ("supervisor-1", "plan")   # the model skipped it
    assert by["LA-1006"]["applicationVersionId"] == 12                                       # for the Assign action


def test_AS_12_2_anIneligibleSuggestionIsReplacedByCodesPlan():
    bad = json.dumps({"suggestions": [{"loanId": "LA-1027", "assignee": "docs-1", "reason": "free"}]})
    by = {i["loanApplicationId"]: i for i in run(Scripted(bad)).items}
    assert (by["LA-1027"]["assignee"], by["LA-1027"]["source"]) == ("income-1", "plan")


def test_AS_12_5_withNoModelEverySuggestionIsCodesPlan():
    result = run(None)
    assert result.mode == "plan" and {i["source"] for i in result.items} == {"plan"}
    assert run(Scripted(ModelUnavailable("down"))).mode == "plan"


def test_FR_08_anUnusableReplyIsRetriedOnceThenCodesPlanStands():
    convo = Scripted("not json", GOOD)
    assert run(convo).mode == "assistant" and convo.sent[1].startswith("That reply was invalid")
    result = run(Scripted("no", "still no"))
    assert result.mode == "invalid" and {i["source"] for i in result.items} == {"plan"}


# ---------------------------------------------------------------- the ADK agent with tools (AS-12.7)

class ToolCallingLlm(BaseLlm):
    """An ADK model that first calls a tool, then answers: so the real runner executes our tool."""
    requests: list[Any] = Field(default_factory=list)

    async def generate_content_async(self, llm_request, stream: bool = False):
        self.requests.append(llm_request)
        if len(self.requests) == 1:
            yield LlmResponse(content=types.Content(role="model", parts=[
                types.Part(function_call=types.FunctionCall(name="unassigned_deals", args={}))]))
        else:
            yield LlmResponse(content=types.Content(role="model", parts=[types.Part(text=GOOD)]))


def test_AS_12_7_theAgentCallsItsReadOnlyToolsThroughAdk():
    facts = deal_facts(READY, USERS, NOW)
    llm = ToolCallingLlm(model="scripted")
    result = asyncio.run(suggest(AdkConversation(llm, instruction=INSTRUCTION, name="assign", tools=tools_for(facts, USERS)),
                                 READY, USERS, NOW))
    assert result.mode == "assistant"
    declared = {f.name for t in llm.requests[0].config.tools for f in t.function_declarations}
    assert declared == {"unassigned_deals", "users", "eligible"}
    returned = [p.function_response for c in llm.requests[1].contents for p in c.parts if p.function_response]
    assert returned and returned[0].name == "unassigned_deals"
    assert "LA-1007" in json.dumps(returned[0].response)


# ---------------------------------------------------------------- the panel and the button (T255)

@pytest.mark.parametrize("state", ["working", "assistant", "plan", "invalid"])
def test_AS_12_4_everyPanelStatePassesStrictValidation(state):
    items = [] if state == "working" else run(None).items
    messages = assign_surface(state, items, {u["id"]: u["role"] for u in USERS})
    validate(messages)
    if state != "working":
        lst = next(c for m in messages if "updateComponents" in m for c in m["updateComponents"]["components"]
                   if c["id"] == "list")
        assert lst["assignAction"] == ASSIGN_ACTION


def test_AS_12_6_onlyTheSupervisorsMyWorkOffersSuggestAssignments():
    def ids(supervisor):
        return {c["id"] for m in worklist_surface(work_model([], []), supervisor=supervisor)
                if "updateComponents" in m for c in m["updateComponents"]["components"]}
    assert "suggest_button" in ids(True) and "suggest_button" not in ids(False)
    fake = FakeFunderApi()
    fake.role = "SUPERVISOR"
    assert "suggest_button" in {c["id"] for m in route(token_for("supervisor-1"), "my work", [], fake.factory())
                                if "updateComponents" in m for c in m["updateComponents"]["components"]}


def test_AS_12_4_assigningIsTheNormalReassignThenMyWorkRefreshed():
    fake = FakeFunderApi()
    fake.role = "SUPERVISOR"
    reply = route(token_for("supervisor-1"), "", [{"name": ASSIGN_ACTION, "context": {
        "loanApplicationId": "LA-1006", "applicationVersionId": 12, "assignee": "docs-1"}}], fake.factory())
    puts = [r for r in fake.requests if r.method == "PUT"]
    assert len(puts) == 1 and puts[0].url.path.endswith("/versions/12/assignee")
    assert json.loads(puts[0].content) == {"funderId": "docs-1"}
    created = [m["createSurface"]["surfaceId"] for m in reply if "createSurface" in m]
    assert created == ["worklist", "notice"]


def test_AS_12_1_codesInTheModelsReasonAreShownAsWords():
    from funder_agent.surfaces.assign import plain
    assert plain("DOCUMENTS work with 3 days left; docs-1 is the DOCUMENTS_SPECIALIST and has 1 open deal.") == \
        "Documents work with 3 days left; docs-1 is the documents specialist and has 1 open deal."
    assert plain("GAP work; gap-1 is free") == "GAP work; gap-1 is free"
    assert plain("risc-1 is the RISC_SPECIALIST") == "risc-1 is the RISC specialist"
