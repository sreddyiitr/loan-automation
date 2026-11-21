"""``deal.summarize`` (spec 002 AS-8.1, AS-8.4; T154): the assistant panel, streamed. It yields
``(messages, final)``: "Summarizing…" at once (not final), then one final panel with the answer.
Each is a whole panel. The overview isn't touched, so it never waits for the model (NFR-02)."""
from __future__ import annotations

from typing import Any, AsyncIterator, Tuple

from ..api import ApiError, FunderApi, message_for
from ..assistant.service import SummaryService
from ..assistant.summary import summary_input
from ..catalog import VERSION
from ..surfaces.assistant import assistant_surface, surface_id
from ..surfaces.notice import notice_surface

Messages = list[dict[str, Any]]
Step = Tuple[Messages, bool]


async def summarize_deal(api: FunderApi, context: dict[str, Any], service: SummaryService) -> AsyncIterator[Step]:
    version_id = int(context["applicationVersionId"])
    yield assistant_surface(version_id, "working"), False
    if not await service.available():
        yield assistant_surface(version_id, "unavailable"), True
        return
    try:
        overview = api.overview(context["loanApplicationId"])
        data = summary_input(overview, api.stipulations(version_id), api.validation_results(version_id))
    except ApiError as e:            # e.g. signed out meanwhile: the panel goes, a notice says why
        yield [{"version": VERSION, "deleteSurface": {"surfaceId": surface_id(version_id)}},
               *notice_surface(message_for(e.reason))], True
        return
    answer = await service.summarize(data)
    yield assistant_surface(version_id, answer.kind, answer.summary), True


STREAMED = {"deal.summarize": summarize_deal}
