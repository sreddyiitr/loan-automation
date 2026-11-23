"""Draft stip text (spec 002 AS-8.3, D3b): the model proposes a description for a manual stip; the
funder edits it before adding. Same path as Summarize: this deal's trimmed data only (AS-8.5), JSON
checked by code, one retry (FR-08), replayed in tests (AS-10.5)."""
from __future__ import annotations

import json
from typing import Any

from .summary import Answer, Conversation, InvalidAnswer, ModelUnavailable, _FENCE, summary_input

MAX_CHARS = 300
INSTRUCTION = """You draft stipulation text for a bank funder reviewing an auto loan.
A stipulation tells the dealer exactly what to send or fix before the loan can fund.
Write one or two short, specific sentences addressed to the dealer, using only the deal data given.
No greetings, no names, no amounts you weren't given."""
REPLY_FORMAT = 'Reply with only a JSON object, nothing else: {"description": "<1-2 sentences>"}'


def draft_input(overview: dict[str, Any], stips: list[dict[str, Any]], results: list[dict[str, Any]],
                code: str, label: str) -> dict[str, Any]:
    """Summarize's trimmed deal data, plus the stip the funder picked."""
    return {**summary_input(overview, stips, results), "stipToDraft": {"code": code, "meaning": label}}


def parse(reply: str) -> str:
    text = reply.strip()
    m = _FENCE.match(text)
    text = m.group(1).strip() if m else text
    try:
        obj = json.loads(text)
    except json.JSONDecodeError as e:
        raise InvalidAnswer(f"the reply wasn't valid JSON ({e.msg})") from None
    if not isinstance(obj, dict) or set(obj) != {"description"} or not isinstance(obj["description"], str):
        raise InvalidAnswer('the reply must be a JSON object with exactly one string key, "description"')
    d = " ".join(obj["description"].split())
    if not d or len(d) > MAX_CHARS:
        raise InvalidAnswer(f'"description" must be 1-{MAX_CHARS} characters; it was {len(d)}')
    return d


async def draft(conversation: Conversation, data: dict[str, Any]) -> Answer:
    message, error = f"{REPLY_FORMAT}\n\nDEAL DATA (JSON):\n{json.dumps(data)}", ""
    for attempt in (1, 2):
        try:
            reply = await conversation.send(message)
        except ModelUnavailable as e:
            return Answer("unavailable", attempts=attempt, error=str(e))
        try:
            return Answer("ok", parse(reply), attempts=attempt)
        except InvalidAnswer as e:
            error = str(e)
            message = f"That reply was invalid: {error}. {REPLY_FORMAT}"
    return Answer("invalid", attempts=2, error=error)
