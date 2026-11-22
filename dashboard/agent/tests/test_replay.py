"""Recorded answers from the real model, replayed (spec 002 AS-10.5, T155). Recorded with
``make record-assistant``; these tests need no model."""
from __future__ import annotations

import asyncio
import json
import re
from pathlib import Path

import pytest

from conftest import FakeFunderApi, OVERVIEW, token_for
from funder_agent.actions.assistant import summarize_deal
from funder_agent.assistant.replay import ReplayService, load_recording
from funder_agent.assistant.summary import parse, summary_input
from funder_agent.assistant.models import Registry
from test_summary import RESULTS, STIPS

RECORDINGS = Path(__file__).resolve().parent.parent / "recordings"
FILES = sorted((RECORDINGS / "summary").glob("LA-*.json"))
DEAL_DATA = re.compile(r"DEAL DATA \(JSON\):\n(.*)$", re.DOTALL)


def recorded(path: Path) -> dict:
    return json.loads(path.read_text())


def data_sent(rec: dict) -> dict:
    return json.loads(DEAL_DATA.search(rec["sent"][0]).group(1))


def panel_after(loan: str, overview: dict) -> dict:
    fake = FakeFunderApi(overview)
    fake.stips, fake.results = STIPS, RESULTS

    async def run():
        with fake.factory()(token_for("docs-1")) as api:
            steps = [s async for s in summarize_deal(api, {"loanApplicationId": loan, "applicationVersionId": 12},
                                                    ReplayService(RECORDINGS))]
        return steps[-1][0]
    last = asyncio.run(run())
    return next(m["updateDataModel"]["value"] for m in last if "updateDataModel" in m)


def test_AS_10_5_thereAreRecordingsForEveryKindOfDeal():
    statuses = {recorded(p)["status"] for p in FILES}
    assert len(FILES) >= 7
    assert {"STIPULATED", "PENDING_REVIEW", "APPROVED", "FUNDED"} <= statuses


@pytest.mark.parametrize("path", FILES, ids=lambda p: p.stem)
def test_AS_8_1_everyRecordedAnswerFromTheRealModelIsValid(path):
    rec = recorded(path)
    assert rec["model"] == Registry.load().for_feature("summary").spec.model   # recorded on the configured model
    assert parse(rec["replies"][-1]) == rec["summary"]
    assert rec["loanApplicationId"] in rec["summary"]


@pytest.mark.parametrize("path", FILES, ids=lambda p: p.stem)
def test_AS_8_5_eachRecordingSentOnlyItsOwnDealAndNoNamesOrSsn(path):
    rec = recorded(path)
    text = "\n".join(rec["sent"])
    assert set(re.findall(r"LA-\d+", text)) == {rec["loanApplicationId"]}
    for absent in ("borrower", "Rivera", "ssn", "applicationVersionId", "document_ids"):
        assert absent.lower() not in text.lower(), absent


@pytest.mark.parametrize("path", FILES, ids=lambda p: p.stem)
def test_AS_10_5_recordingsMatchWhatTheCodeSendsToday(path):
    """If summary_input changes shape, the recordings are stale: rerun make record-assistant."""
    sent, today = data_sent(recorded(path)), summary_input(OVERVIEW, STIPS, RESULTS)
    assert set(sent) == set(today), "rerun make record-assistant"
    assert set(sent["deal"]) == set(today["deal"]), "rerun make record-assistant"


def test_AS_10_5_replayPlaysTheRecordedSummaryThroughTheRealFlow():
    rec = load_recording(RECORDINGS, "LA-1006")
    panel = panel_after("LA-1006", OVERVIEW)
    assert (panel["state"], panel["summary"]) == ("ok", rec["summary"])


def test_AS_8_4_inReplayADealWithNoRecordingIsAssistantUnavailable():
    assert load_recording(RECORDINGS, "LA-1002") is None
    panel = panel_after("LA-1002", OVERVIEW | {"loanApplicationId": "LA-1002"})
    assert panel["state"] == "unavailable"


def test_AS_10_5_assistantReplayPicksReplayInsteadOfAModel(monkeypatch):
    monkeypatch.setattr("funder_agent.config.ASSISTANT_REPLAY", str(RECORDINGS))
    from funder_agent.assistant.service import SummaryService
    service = SummaryService.from_config()
    assert isinstance(service, ReplayService)
    assert asyncio.run(service.available()) is True                  # no health check: there's no model
