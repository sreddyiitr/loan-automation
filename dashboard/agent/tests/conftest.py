"""Shared test helpers: a strict A2UI validator, sample funder API answers, and a fake funder API."""
from __future__ import annotations

import base64
import json
from typing import Any

import httpx
import pytest
from a2ui.core import MessageProcessor, MessageProcessorOptions, STRICT_VALIDATION

from funder_agent.api import FunderApi
from funder_agent.catalog import validation_catalog


def validate(*batches: list[dict[str, Any]]) -> None:
    """NFR-05: the messages pass A2UI v1.0 strict validation against our catalog, applied in order to
    one renderer state, as the browser would (e.g. a first screen, then a reply that rebuilds it)."""
    p = MessageProcessor([validation_catalog()], options=MessageProcessorOptions(validation_config=STRICT_VALIDATION))
    for messages in batches:
        p.process_messages(messages)


def token_for(user: str) -> str:
    """An unsigned JWT-shaped token: the agent only reads sub; the funder API checks signatures."""
    enc = lambda d: base64.urlsafe_b64encode(json.dumps(d).encode()).decode().rstrip("=")
    return f"{enc({'alg': 'none'})}.{enc({'sub': user, 'aud': 'funding-api'})}.sig"


OVERVIEW = {
    "loanApplicationId": "LA-1006", "applicationVersionId": 12, "latestVersionNumber": 2, "status": "STIPULATED",
    "dealershipId": "DLR-100", "dealershipName": "Lone Star Motors", "borrowerName": "Maria Lopez",
    "coBorrowerName": None, "statedIncomeAnnual": 85000.0, "vehicleVin": "1HGCM82633A004352",
    "assignedFunder": None, "submittedAt": "2026-11-21T05:44:33.169612Z", "expiresAt": "2026-12-21T05:44:33.169612Z",
    "workflowId": "funding-local-12", "workflowStep": "WAITING_FOR_FUNDER", "inProgress": False,
    "payment": {"state": "NOT_STARTED"},
    # spec 002 T205: routing and what the viewer (docs-1) may do
    "workKinds": ["DOCUMENTS"], "routedTo": "DOCUMENTS_SPECIALIST",
    "viewer": {"id": "docs-1", "role": "DOCUMENTS_SPECIALIST"},
    "allowedActions": [{"action": "VIEW", "allowed": True, "reason": None},
                       {"action": "TAKE", "allowed": True, "reason": None},
                       {"action": "RESOLVE_STIP", "allowed": False, "reason": "NOT_ASSIGNED_TO_YOU"},
                       {"action": "REASSIGN", "allowed": False, "reason": "ROLE_NOT_ALLOWED"}],
}
VERSIONS = [
    {"applicationVersionId": 12, "versionNumber": 2, "isLatestVersion": True, "versionStatus": "STIPULATED",
     "submittedAt": "2026-11-21T05:44:33Z", "assignedFunder": None},
    {"applicationVersionId": 7, "versionNumber": 1, "isLatestVersion": False, "versionStatus": "STIPULATED",
     "submittedAt": "2026-11-19T10:00:00Z", "assignedFunder": None},
]


STIP_CODES = [{"code": "STIP_PRICE_MISMATCH", "description": "Price differs between the RISC and the Buyer's Order"},
              {"code": "STIP_TILA_MATH_DISCREPANCY", "description": "TILA math doesn't add up"},
              {"code": "STIP_OTHER", "description": "Something else"}]


class FakeFunderApi:
    """Answers like the funder API, records every request, and can refuse with a problem+json reason."""

    def __init__(self, overview: dict[str, Any] | None = None, refuse: tuple[int, str] | None = None):
        self.overview = dict(overview or OVERVIEW)
        self.refuse = refuse
        self.stips: list[dict[str, Any]] = []
        self.results: list[dict[str, Any]] = []
        self.audit: list[dict[str, Any]] = []
        self.queues: dict[str, list[dict[str, Any]]] = {"mine": [], "ready": []}
        self.role = "DOCUMENTS_SPECIALIST"
        self.users: list[dict[str, Any]] = []
        self.requests: list[httpx.Request] = []

    def handler(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        if self.refuse:
            return httpx.Response(self.refuse[0], json={"reason": self.refuse[1], "detail": "refused"},
                                  headers={"Content-Type": "application/problem+json"})
        path = request.url.path.removeprefix("/api/v1")
        if request.method == "GET" and path == f"/applications/{self.overview['loanApplicationId']}":
            return httpx.Response(200, json=self.overview)
        if request.method == "GET" and path == "/me":
            sub = json.loads(base64.urlsafe_b64decode(request.headers["Authorization"].split(".")[1] + "=="))["sub"]
            return httpx.Response(200, json={"id": sub, "role": self.role})
        if request.method == "GET" and path == "/users":                # US-12, supervisor only
            if self.role != "SUPERVISOR":
                return httpx.Response(403, json={"reason": "ROLE_NOT_ALLOWED", "detail": "supervisor only"})
            return httpx.Response(200, json=self.users)
        if request.method == "GET" and path == "/applications":       # AS-1.8 My work
            return httpx.Response(200, json=self.queues.get(request.url.params.get("queue"), []))
        if request.method == "GET" and path.endswith("/versions"):
            return httpx.Response(200, json=VERSIONS)
        if request.method == "GET" and path.endswith("/stipulations"):
            return httpx.Response(200, json=self.stips)
        if request.method == "GET" and path.endswith("/validation-results"):
            return httpx.Response(200, json=self.results)
        if request.method == "GET" and path.endswith("/audit"):
            return httpx.Response(200, json=self.audit)
        if request.method == "POST" and path.endswith("/take"):
            me = json.loads(base64.urlsafe_b64decode(request.headers["Authorization"].split(".")[1] + "=="))["sub"]
            if self.overview["assignedFunder"] not in (None, me):
                return httpx.Response(409, json={"reason": "ALREADY_ASSIGNED", "detail": "taken"})
            self.overview["assignedFunder"] = me
            return httpx.Response(200, json={"assignedFunder": me})
        if request.method == "GET" and path == "/stip-codes":           # D3, D-19
            return httpx.Response(200, json=STIP_CODES)
        if request.method == "PATCH" and "/stipulations/" in path:     # AS-3.2, AS-3.4
            stip = next(s for s in self.stips if s["stipId"] == int(path.rsplit("/", 1)[1]))
            if stip["status"] != "PENDING":
                return httpx.Response(409, json={"reason": "ALREADY_RESOLVED", "detail": "resolved"})
            body = json.loads(request.content)
            stip |= {"status": body["status"], "resolutionNote": body["reason"], "resolvedBy": "funder-1"}
            return httpx.Response(200, json=stip)
        if request.method == "POST" and path.endswith("/stipulations"):  # AS-3.5
            body = json.loads(request.content)
            if body["code"] not in {c["code"] for c in STIP_CODES}:
                return httpx.Response(400, json={"reason": "UNKNOWN_STIP_CODE", "detail": body["code"]})
            self.stips.append({"stipId": len(self.stips) + 1, "stipCode": body["code"], "description": body["description"],
                               "status": "PENDING", "waivable": True})
            return httpx.Response(201, json=self.stips[-1])
        if request.method == "PUT" and path.endswith("/assignee"):
            self.overview["assignedFunder"] = json.loads(request.content)["funderId"]
            return httpx.Response(200, json={"assignedFunder": self.overview["assignedFunder"]})
        return httpx.Response(404, json={"reason": "NOT_FOUND", "detail": path})

    def factory(self):
        return lambda token: FunderApi(token, base_url="http://funder.test/api/v1", transport=httpx.MockTransport(self.handler))


@pytest.fixture
def fake() -> FakeFunderApi:
    return FakeFunderApi()
