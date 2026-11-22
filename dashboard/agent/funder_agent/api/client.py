"""The funder API client (plan §2, spec 002 FR-11).

It relays the signed-in user's own bearer token on every call and never creates one. The funder
API checks the token; the agent only reads its ``sub`` to know who "me" is (e.g. to take a deal).
"""
from __future__ import annotations

import base64
import json
from typing import Any

import httpx

from .. import config


class NotSignedIn(Exception):
    """The browser sent no bearer token."""


class ApiError(Exception):
    """A problem+json answer from the funder API: its HTTP status and ``reason`` code."""

    def __init__(self, status: int, reason: str, detail: str = ""):
        super().__init__(f"{status} {reason}: {detail}")
        self.status, self.reason, self.detail = status, reason, detail


def user_id(token: str) -> str:
    """The token's ``sub``. Read without checking the signature: the funder API checks it on every call."""
    try:
        payload = token.split(".")[1]
        return json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))["sub"]
    except (IndexError, KeyError, ValueError) as e:
        raise NotSignedIn("the bearer token is not a JWT with a sub") from e


class FunderApi:
    """One user's view of the funder API. ``transport`` lets tests answer without a server."""

    def __init__(self, token: str | None, base_url: str = config.FUNDER_API_URL,
                 transport: httpx.BaseTransport | None = None):
        if not token:
            raise NotSignedIn("no bearer token")
        self.token = token
        self._http = httpx.Client(base_url=base_url, timeout=10, transport=transport,
                                  headers={"Authorization": f"Bearer {token}"})

    def __enter__(self) -> "FunderApi":
        return self

    def __exit__(self, *exc: object) -> None:
        self._http.close()

    @property
    def me(self) -> str:
        return user_id(self.token)

    # ------------------------------------------------------------------ reads

    def overview(self, loan_id: str) -> dict[str, Any]:
        return self._call("GET", f"/applications/{loan_id}")

    def worklist(self, queue: str) -> list[dict[str, Any]]:
        """Spec 002 AS-1.8: ``mine`` (assigned to me) or ``ready`` (routed to me, nobody has it)."""
        return self._call("GET", "/applications", params={"queue": queue})

    def versions(self, loan_id: str) -> list[dict[str, Any]]:
        return self._call("GET", f"/applications/{loan_id}/versions")

    def stipulations(self, version_id: int) -> list[dict[str, Any]]:
        return self._call("GET", f"/versions/{version_id}/stipulations")

    def validation_results(self, version_id: int) -> list[dict[str, Any]]:
        return self._call("GET", f"/versions/{version_id}/validation-results")

    def audit(self, version_id: int) -> list[dict[str, Any]]:
        return self._call("GET", f"/versions/{version_id}/audit")

    # ------------------------------------------------------------------ writes

    def take(self, version_id: int) -> dict[str, Any]:
        """AS-2.6: assign to the caller, only if nobody has it (409 ALREADY_ASSIGNED otherwise)."""
        return self._call("POST", f"/versions/{version_id}/take")

    def assign(self, version_id: int, funder_id: str) -> dict[str, Any]:
        """AS-2.4: a supervisor's reassign (role rule in D2)."""
        return self._call("PUT", f"/versions/{version_id}/assignee", json={"funderId": funder_id})

    # ------------------------------------------------------------------ helpers

    def _call(self, method: str, path: str, **kwargs: Any) -> Any:
        r = self._http.request(method, path, **kwargs)
        if r.is_success:
            return r.json()
        try:
            body = r.json()
        except ValueError:
            body = {}
        raise ApiError(r.status_code, body.get("reason") or f"HTTP_{r.status_code}", body.get("detail", ""))
