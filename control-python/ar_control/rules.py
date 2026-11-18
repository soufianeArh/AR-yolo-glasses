"""
Turning detections into alerts. Ported from soufiane-easy's rule engine (logic, not code).

A frame at 8 fps with a cow in view produces 8 "cow" detections a second - every one true,
none worth a new notification. This module is the reduction from "what the model saw" to
"what a human should look at", and most of it is de-duplication. Three filters, in order:

  0. label      the detection's label == rule.label, or CONTAINS it (rule.match = 'contains',
                e.g. "blight" covers Corn leaf blight, Tomato leaf late blight, Potato leaf ...)
  1. threshold  confidence >= rule.min_confidence     (is the model sure?)
  2. quorum     at least rule.min_count in ONE frame  (is it significant?)
  3. cooldown   nothing from this rule for this DEVICE in the last N seconds
                (have we already said so?)

Differences from soufiane-easy, on purpose:
  - Cooldown is per (rule, device): two pairs of glasses seeing a cow are two events.
  - Time = when the scheduler RECEIVED the frame, not when this code runs. After an outage the
    consumer catches up on a backlog; using wall-clock time would then fire one alert for the
    whole backlog instead of the alerts that should have fired as it happened.
  - Alerts raised inside one batch count immediately for the frames after them in the same
    batch, but only become "real" (cached) once the batch's transaction commits.
"""
import threading
import time
from dataclasses import dataclass
from datetime import datetime, timedelta

from sqlalchemy import func, select

from .db import alert, alert_rule

SEVERITIES = ("low", "medium", "high")
_NEVER = datetime.min


@dataclass(frozen=True)
class Rule:
    id: int
    name: str
    label: str
    match: str              # 'exact' or 'contains'
    min_confidence: float
    min_count: int
    cooldown_seconds: int
    severity: str
    device_id: str | None
    enabled: bool


@dataclass(frozen=True)
class Hit:
    """One detection, as the rule engine needs it."""
    label: str
    confidence: float


class RuleEngine:
    def __init__(self, reload_seconds=5.0):
        self.reload_seconds = reload_seconds
        self._rules: list[Rule] = []
        self._loaded_at = 0.0
        self._last_fired: dict[tuple[int, str], datetime] = {}   # (rule_id, device) -> raised_at
        self._pending: dict[tuple[int, str], datetime] = {}      # fired in the current batch
        self._lock = threading.Lock()

    # ------------------------------------------------------------------ rules

    def rules(self, conn) -> list[Rule]:
        """Enabled rules, reloaded from the database every few seconds (the API edits them)."""
        if time.monotonic() - self._loaded_at >= self.reload_seconds:
            rows = conn.execute(select(alert_rule).where(alert_rule.c.enabled.is_(True))).mappings().all()
            self._rules = [Rule(**{k: r[k] for k in Rule.__dataclass_fields__}) for r in rows]
            self._loaded_at = time.monotonic()
        return self._rules

    def force_reload(self):
        self._loaded_at = 0.0

    # ------------------------------------------------------------------ batches

    def begin_batch(self):
        self._pending = {}

    def commit_batch(self):
        with self._lock:
            self._last_fired.update(self._pending)
        self._pending = {}

    def rollback_batch(self):
        self._pending = {}

    # ------------------------------------------------------------------ evaluation

    def evaluate(self, conn, device: str, received_at: datetime, session_id: str, frame_id: int,
                 hits: list[Hit]) -> list[dict]:
        """Alerts this frame raises (as rows to insert). Call between begin_batch and commit_batch."""
        if not hits:
            return []
        by_label: dict[str, list[Hit]] = {}
        for h in hits:
            # lowercase on both sides: rules are stored lowercased, model labels may not be
            by_label.setdefault(h.label.lower(), []).append(h)

        raised = []
        for rule in self.rules(conn):
            if rule.device_id is not None and rule.device_id != device:
                continue
            if rule.match == "contains":
                pool = [h for lab, hs in by_label.items() if rule.label in lab for h in hs]
            else:
                pool = by_label.get(rule.label, [])
            candidates = [h for h in pool if h.confidence >= rule.min_confidence]
            if len(candidates) < rule.min_count:
                continue
            if not self._cooldown_expired(conn, rule, device, received_at):
                continue
            self._pending[(rule.id, device)] = received_at
            top = max(candidates, key=lambda h: h.confidence)
            raised.append({
                "rule_id": rule.id, "rule_name": rule.name, "device_id": device, "session_id": session_id,
                # the label the model actually emitted (for 'contains' rules: e.g. "Tomato leaf late blight")
                "frame_id": frame_id, "label": top.label if rule.match == "contains" else rule.label,
                "count": len(candidates), "max_confidence": top.confidence, "severity": rule.severity,
                "raised_at": received_at,
            })
        return raised

    def _cooldown_expired(self, conn, rule: Rule, device: str, at: datetime) -> bool:
        key = (rule.id, device)
        last = self._pending.get(key)
        if last is None:
            with self._lock:
                last = self._last_fired.get(key)
        if last is None:
            # Cold cache (restart, or first frame for this pair): ask the alert table, otherwise
            # every restart would re-announce everything currently in view.
            last = conn.execute(
                select(func.max(alert.c.raised_at))
                .where(alert.c.rule_id == rule.id, alert.c.device_id == device)
            ).scalar() or _NEVER
            with self._lock:
                self._last_fired.setdefault(key, last)
        if last is _NEVER:
            return True
        return at - last >= timedelta(seconds=rule.cooldown_seconds)
