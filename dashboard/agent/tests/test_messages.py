"""FR-06 (T303): every reason the funder API can return has a message in ``messages.yaml``.

The reasons are read from the funder API's Java source, so a new reason there without a message
here fails this test by name. Reasons built at run time are allowed only in the forms listed in
``DYNAMIC``, each expanded from its own source of truth.
"""
import re
from pathlib import Path

from funder_agent.api import message_for
from funder_agent.api.messages import FALLBACK, messages

REPO = Path(__file__).resolve().parents[3]
API = REPO / "funding-api/src/main/java/com/funding/api"
PERMISSIONS = REPO / "funding-domain/src/main/java/com/funding/domain/routing/Permissions.java"
# What the funder endpoints run: their controllers and service, sign-in, and the shared error handler.
SOURCES = [*sorted((API / "funder").glob("*.java")), *sorted((API / "auth").glob("*.java")), API / "ProblemHandler.java"]

PROBLEM = re.compile(r'new ApiProblem\(\s*HttpStatus\.\w+,\s*([^,]+?),')
LITERAL = re.compile(r'^"([A-Z][A-Z_]*)"$')
RAW = [re.compile(r'problem\(resp, \d+, "([A-Z_]+)"'), re.compile(r'setProperty\("reason", "([A-Z_]+)"\)')]
REQUIRE_TEXT = re.compile(r'requireText\(\w+, "(\w+)"\)')
CONSTANT = re.compile(r'public static final String (\w+) = "\1";')
# Non-literal reasons and where their values come from.
DYNAMIC = {
    "d.reason()": "Permissions' refusal constants",
    '"MISSING_" + name.toUpperCase(java.util.Locale.ROOT)': "requireText(..., name) calls",
}


def api_reasons() -> set[str]:
    reasons: set[str] = set()
    for src in SOURCES:
        text = src.read_text()
        for arg in PROBLEM.findall(text):
            arg = arg.strip()
            if m := LITERAL.match(arg):
                reasons.add(m.group(1))
            else:
                assert arg in DYNAMIC, f"{src.name}: reason {arg} is built at run time; teach test_messages.py where it comes from"
        for pattern in RAW:
            reasons.update(pattern.findall(text))
        reasons.update(f"MISSING_{name.upper()}" for name in REQUIRE_TEXT.findall(text))
    reasons.update(CONSTANT.findall(PERMISSIONS.read_text()))
    return reasons


def test_FR_06_theScanFindsTheApisReasons():
    # A guard against a scan that silently finds nothing after a move or a rename.
    found = api_reasons()
    assert {"UNAUTHORIZED", "UNKNOWN_USER", "MALFORMED", "ALREADY_ASSIGNED", "NOT_YOUR_SPECIALTY",
            "UNKNOWN_STIP_CODE", "MISSING_REASON", "MISSING_FUNDERID"} <= found
    assert len(found) >= 30


def test_FR_06_everyFunderApiReasonHasAMessage():
    missing = sorted(api_reasons() - messages().keys())
    assert not missing, f"add these reasons to dashboard/agent/messages.yaml: {missing}"


def test_FR_06_everyMessageIsPlainText():
    for reason, text in messages().items():
        assert isinstance(text, str) and text.strip(), reason
        assert message_for(reason) == text != FALLBACK.format(reason=reason)
        assert reason not in text, f"{reason}: the message shouldn't show the code to the funder"


def test_FR_06_noMessageForAReasonTheApiDoesntHave():
    # Catches a typo in a key, or a reason the API has since dropped.
    stale = sorted(messages().keys() - api_reasons())
    assert not stale, f"no funder API reason matches these messages.yaml keys: {stale}"
