"""The model registry (spec 002 FR-07, T152): models are configuration, never code."""
from __future__ import annotations

import re
from pathlib import Path

import pytest
import yaml

from funder_agent.assistant.models import FEATURES, Registry, RegistryError

AGENT = Path(__file__).resolve().parent.parent
DEFAULT = AGENT / "models.yaml"


def default_raw() -> dict:
    return yaml.safe_load(DEFAULT.read_text())


def write(tmp_path: Path, raw: dict) -> Path:
    path = tmp_path / "models.yaml"
    path.write_text(yaml.safe_dump(raw))
    return path


def test_FR_07_theDefaultIsLocalGemmaForEveryFeatureAtNoCost():
    registry = Registry.load(DEFAULT)
    for feature in FEATURES:
        spec = registry.for_feature(feature).spec
        assert spec.model == "ollama_chat/gemma4:e4b"
        assert (spec.cost_in, spec.cost_out) == (0, 0)                    # D-7, NFR-03
        assert spec.structured_output is False                           # a2ui-notes §12b: the MLX runner can't


def test_FR_07_summariesDontThinkAndSuggestionsDo():
    registry = Registry.load(DEFAULT)
    summary = registry.for_feature("summary").litellm_args()
    assert summary == {"model": "ollama_chat/gemma4:e4b", "temperature": 0, "reasoning_effort": "none",
                       "api_base": "http://localhost:11434"}             # "none" is what LiteLLM sends as think: false
    assert registry.for_feature("suggest").litellm_args()["reasoning_effort"] == "medium"


def test_FR_07_theAdkModelCarriesTheRegistrysSettings():
    llm = Registry.load(DEFAULT).for_feature("summary").llm()
    assert type(llm).__name__ == "LiteLlm"
    assert llm.model == "ollama_chat/gemma4:e4b"
    assert llm._additional_args["reasoning_effort"] == "none"            # ADK passes these to LiteLLM as they are
    assert llm._additional_args["temperature"] == 0


def test_FR_07_switchingEveryFeaturesModelIsAYamlEdit(tmp_path):
    raw = default_raw()
    raw["models"]["local_gemma"]["model"] = "ollama_chat/llama3.2:3b"
    registry = Registry.load(write(tmp_path, raw))
    assert {registry.for_feature(f).spec.model for f in FEATURES} == {"ollama_chat/llama3.2:3b"}


def test_FR_07_oneFeatureCanUseItsOwnModel(tmp_path):
    raw = default_raw()
    raw["models"]["bigger"] = {"model": "hosted_vllm/qwen3-14b", "api_base": "http://localhost:8000/v1",
                               "context": 32768, "reasoning_effort": {"thinking_off": "none", "thinking_on": "high"}}
    raw["features"]["suggest"] = {"model": "bigger", "thinking": True}
    registry = Registry.load(write(tmp_path, raw))
    assert registry.for_feature("suggest").litellm_args() == {
        "model": "hosted_vllm/qwen3-14b", "temperature": 0, "reasoning_effort": "high", "api_base": "http://localhost:8000/v1"}
    assert registry.for_feature("summary").spec.model == "ollama_chat/gemma4:e4b"   # the others keep theirs


def test_FR_07_theFileComesFromAssistantModels(tmp_path, monkeypatch):
    raw = default_raw()
    raw["models"]["local_gemma"]["model"] = "ollama_chat/gemma4:e2b"
    monkeypatch.setattr("funder_agent.config.ASSISTANT_MODELS", str(write(tmp_path, raw)))
    assert Registry.load().for_feature("summary").spec.model == "ollama_chat/gemma4:e2b"


def test_FR_07_aFeatureThatIsntListedUsesTheDefaultWithThinkingOff(tmp_path):
    raw = default_raw()
    del raw["features"]["draft_stip"]
    feature = Registry.load(write(tmp_path, raw)).for_feature("draft_stip")
    assert (feature.spec.name, feature.thinking) == ("local_gemma", False)


@pytest.mark.parametrize("edit, message", [
    (lambda r: r.update(default="missing"), "default: 'missing' isn't one of the models"),
    (lambda r: r["features"]["summary"].update(model="missing"), "features.summary.model: 'missing'"),
    (lambda r: r["features"].update(chitchat={"model": "local_gemma"}), "features: unknown chitchat"),
    (lambda r: r["features"]["summary"].update(thinking="no"), "features.summary.thinking: expected true or false"),
    (lambda r: r["models"]["local_gemma"].pop("model"), "models.local_gemma.model: a LiteLLM model string is required"),
    (lambda r: r["models"]["local_gemma"].update(reasoning_effort={False: "none", True: "medium"}),
     "models.local_gemma.reasoning_effort: needs thinking_off and thinking_on"),   # what `{off: …, on: …}` loads as
    (lambda r: r.update(models={}), "models: needs at least one model"),
])
def test_FR_07_aBrokenFileIsRefusedNamingTheEntry(tmp_path, edit, message):
    raw = default_raw()
    edit(raw)
    with pytest.raises(RegistryError, match=re.escape(message)):
        Registry.load(write(tmp_path, raw))


def test_FR_07_anUnknownFeatureIsAnError():
    with pytest.raises(RegistryError, match="unknown assistant feature 'chitchat'"):
        Registry.load(DEFAULT).for_feature("chitchat")


def test_FR_07_noProviderOrModelNameAppearsInTheAgentsCode():
    """'No provider name appears in code': they live in models.yaml only."""
    names = re.compile(r"ollama|11434|gemma|openai|anthropic|claude|mistral|vertex_ai|hosted_vllm", re.IGNORECASE)
    hits = [f"{p.relative_to(AGENT)}:{n}: {line.strip()}"
            for p in sorted((AGENT / "funder_agent").rglob("*.py"))
            for n, line in enumerate(p.read_text().splitlines(), 1) if names.search(line)]
    assert hits == []
