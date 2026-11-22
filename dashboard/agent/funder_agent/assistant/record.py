"""``make record-assistant`` (spec 002 T155): record the configured model's summaries of some seeded
deals, for replay in the tests (AS-10.5). Needs the funder API with the seeds submitted, the OIDC
provider and the model running. Rerun it when the prompt or the model changes.

    uv run python -m funder_agent.assistant.record [LA-1006 ...]     # summaries (and, with no ids, assign)
    uv run python -m funder_agent.assistant.record --assign          # US-12 suggestions only
"""
from __future__ import annotations

import asyncio
import json
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

import httpx

from funder_agent import config
from funder_agent.api import FunderApi
from funder_agent.assistant.models import Registry
from funder_agent.assistant.replay import recording_path
from funder_agent.assistant.summary import AdkConversation, summarize, summary_input

RECORDINGS = Path(__file__).resolve().parents[2] / "recordings"
# One deal per kind of story: price, VIN, income, an ERROR hold, low confidence, approved, funded.
DEALS = ["LA-1006", "LA-1007", "LA-1012", "LA-1016", "LA-1023", "LA-1030", "LA-1001"]
ISSUER = "http://127.0.0.1:8090/funding"


class Recording:
    """Wraps a real conversation and keeps what was sent and what came back."""

    def __init__(self, inner):
        self.inner, self.sent, self.replies = inner, [], []

    async def send(self, text: str) -> str:
        self.sent.append(text)
        reply = await self.inner.send(text)
        self.replies.append(reply)
        return reply


def token(user: str = "supervisor-1") -> str:
    r = httpx.post(f"{ISSUER}/token", data={"grant_type": "client_credentials", "client_id": user,
                                            "client_secret": "unused", "scope": "funding-api"})
    r.raise_for_status()
    return r.json()["access_token"]


async def record(loans: list[str]) -> int:
    feature = Registry.load().for_feature("summary")
    failures = 0
    with FunderApi(token()) as api:
        for loan in loans:
            overview = api.overview(loan)
            version = overview["applicationVersionId"]
            data = summary_input(overview, api.stipulations(version), api.validation_results(version))
            convo = Recording(AdkConversation(feature.llm()))
            started = time.monotonic()
            answer = await summarize(convo, data)
            seconds = round(time.monotonic() - started, 2)
            if answer.kind != "ok":
                failures += 1
                print(f"{loan}: {answer.kind} ({answer.error}); not saved", file=sys.stderr)
                continue
            path = recording_path(RECORDINGS, loan)
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(json.dumps({
                "feature": "summary", "loanApplicationId": loan, "status": overview["status"],
                "model": feature.spec.model, "thinking": feature.thinking,
                "recordedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"), "seconds": seconds,
                "sent": convo.sent, "replies": convo.replies, "summary": answer.summary,
            }, indent=2) + "\n")
            print(f"{loan} [{overview['status']}] {seconds}s, {answer.attempts} attempt(s): {answer.summary}")
    return failures


async def record_assign() -> int:
    """US-12: the assign model's suggestions for the seeded unassigned deals, as the supervisor sees them,
    with its real tool calls. Saved as recordings/assign/supervisor.json."""
    from funder_agent.assistant.assign import INSTRUCTION, deal_facts, suggest, tools_for
    feature = Registry.load().for_feature("assign")
    with FunderApi(token("supervisor-1")) as api:
        users, ready = api.users(), api.worklist("ready")
    tools = tools_for(deal_facts(ready, users, datetime.now(timezone.utc)), users)
    convo = Recording(AdkConversation(feature.llm(), instruction=INSTRUCTION, name="assign", tools=tools))
    started = time.monotonic()
    result = await suggest(convo, ready, users)
    seconds = round(time.monotonic() - started, 2)
    if result.mode != "assistant":
        print(f"assign: {result.mode}; not saved", file=sys.stderr)
        return 1
    path = recording_path(RECORDINGS, "supervisor", "assign")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps({
        "feature": "assign", "model": feature.spec.model, "thinking": feature.thinking,
        "recordedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"), "seconds": seconds,
        "sent": convo.sent, "replies": convo.replies, "dropped": result.dropped,
        "fromAssistant": sum(i["source"] == "assistant" for i in result.items), "deals": len(result.items),
    }, indent=2) + "\n")
    print(f"assign: {path.name} ({seconds}s, {len(result.items)} deals, {len(result.dropped)} dropped)")
    return 0


def main() -> None:
    args = sys.argv[1:]
    if args == ["--assign"]:
        sys.exit(asyncio.run(record_assign()))
    failures = asyncio.run(record(args or DEALS)) + (asyncio.run(record_assign()) if not args else 0)
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
