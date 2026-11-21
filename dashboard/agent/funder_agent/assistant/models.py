"""The model registry (spec 002 FR-07, plan §7): which model each assistant feature uses, read from
``models.yaml``. Code asks for a feature ("summary"), never for a provider, so swapping a model is a
YAML edit. Each feature gets an ADK ``LiteLlm`` built from its entry.
"""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Any

import yaml

from funder_agent import config

FEATURES = ("summary", "suggest", "draft_stip", "generated_view")


class RegistryError(ValueError):
    """models.yaml doesn't describe a usable registry; the message names the entry."""


@dataclass(frozen=True)
class ModelSpec:
    name: str
    model: str                      # LiteLLM model string
    api_base: str | None
    health: str | None              # 2xx when the model can be used (AS-8.4)
    context: int
    structured_output: bool
    effort_off: str                 # LiteLLM reasoning_effort with thinking off
    effort_on: str                  # ... and on
    cost_in: float                  # USD per million tokens
    cost_out: float


@dataclass(frozen=True)
class FeatureModel:
    feature: str
    spec: ModelSpec
    thinking: bool

    def litellm_args(self) -> dict[str, Any]:
        """What the feature's LiteLlm is built with: temperature 0, so recorded answers replay (AS-10.5)."""
        args: dict[str, Any] = {
            "model": self.spec.model,
            "temperature": 0,
            "reasoning_effort": self.spec.effort_on if self.thinking else self.spec.effort_off,
        }
        if self.spec.api_base:
            args["api_base"] = self.spec.api_base
        return args

    def llm(self):
        """The ADK model for this feature."""
        import litellm
        from google.adk.models.lite_llm import LiteLlm   # imported here: loading LiteLLM takes ~1 s
        litellm.suppress_debug_info = True               # no "Provider List" banner in the agent's log per call
        return LiteLlm(**self.litellm_args())


class Registry:
    def __init__(self, models: dict[str, ModelSpec], features: dict[str, FeatureModel], default: str):
        self.models, self.features, self.default = models, features, default

    def for_feature(self, feature: str) -> FeatureModel:
        if feature not in self.features:
            raise RegistryError(f"unknown assistant feature {feature!r}; known: {', '.join(FEATURES)}")
        return self.features[feature]

    @classmethod
    def load(cls, path: str | Path | None = None) -> "Registry":
        path = Path(path or config.ASSISTANT_MODELS)
        try:
            raw = yaml.safe_load(path.read_text())
        except FileNotFoundError:
            raise RegistryError(f"{path}: not found") from None
        return cls.parse(raw, str(path))

    @classmethod
    def parse(cls, raw: Any, where: str = "models.yaml") -> "Registry":
        if not isinstance(raw, dict):
            raise RegistryError(f"{where}: expected a mapping with default, models and features")
        models_raw = raw.get("models") or {}
        if not isinstance(models_raw, dict) or not models_raw:
            raise RegistryError(f"{where}: models: needs at least one model")
        models = {name: _model(name, m, where) for name, m in models_raw.items()}

        default = raw.get("default")
        if default not in models:
            raise RegistryError(f"{where}: default: {default!r} isn't one of the models ({', '.join(models)})")

        features_raw = raw.get("features") or {}
        if not isinstance(features_raw, dict):
            raise RegistryError(f"{where}: features: expected a mapping")
        unknown = sorted(set(features_raw) - set(FEATURES))
        if unknown:
            raise RegistryError(f"{where}: features: unknown {', '.join(unknown)}; known: {', '.join(FEATURES)}")
        features = {}
        for feature in FEATURES:           # a feature that isn't listed uses the default model, thinking off
            entry = features_raw.get(feature) or {}
            if not isinstance(entry, dict):
                raise RegistryError(f"{where}: features.{feature}: expected {{model, thinking}}")
            name = entry.get("model", default)
            if name not in models:
                raise RegistryError(f"{where}: features.{feature}.model: {name!r} isn't one of the models")
            thinking = entry.get("thinking", False)
            if not isinstance(thinking, bool):
                raise RegistryError(f"{where}: features.{feature}.thinking: expected true or false")
            features[feature] = FeatureModel(feature, models[name], thinking)
        return cls(models, features, default)


def _model(name: str, m: Any, where: str) -> ModelSpec:
    at = f"{where}: models.{name}"
    if not isinstance(m, dict) or not isinstance(m.get("model"), str) or not m["model"]:
        raise RegistryError(f"{at}.model: a LiteLLM model string is required")
    effort = m.get("reasoning_effort") or {}
    if not isinstance(effort, dict) or not all(isinstance(effort.get(k), str) for k in ("thinking_off", "thinking_on")):
        raise RegistryError(f"{at}.reasoning_effort: needs thinking_off and thinking_on levels (strings)")
    cost = m.get("cost_per_mtok") or {}
    return ModelSpec(
        name=name, model=m["model"], api_base=m.get("api_base"), health=m.get("health"),
        context=int(m.get("context", 4096)), structured_output=bool(m.get("structured_output", False)),
        effort_off=effort["thinking_off"], effort_on=effort["thinking_on"],
        cost_in=float(cost.get("in", 0)), cost_out=float(cost.get("out", 0)),
    )
