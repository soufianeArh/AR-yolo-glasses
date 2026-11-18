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
    match: str
    min_confidence: float
    # boxes of that label needed in one frame
    min_count: int
    cooldown_seconds: int
    severity: str
    device_id: str | None
    enabled: bool


@dataclass(frozen=True)
class Hit:
    label: str
    confidence: float


class RuleEngine:
    def __init__(self, reload_seconds=5.0):
        self.reload_seconds = reload_seconds
        self._rules: list[Rule] = []
        self._loaded_at = 0.0
        self._last_fired: dict[tuple[int, str], datetime] = {}
        self._pending: dict[tuple[int, str], datetime] = {}
        self._lock = threading.Lock()


    def rules(self, conn) -> list[Rule]:
        if time.monotonic() - self._loaded_at >= self.reload_seconds:
            rows = conn.execute(select(alert_rule).where(alert_rule.c.enabled.is_(True))).mappings().all()
            self._rules = [Rule(**{k: r[k] for k in Rule.__dataclass_fields__}) for r in rows]
            self._loaded_at = time.monotonic()
        return self._rules

    def force_reload(self):
        self._loaded_at = 0.0


    def begin_batch(self):
        self._pending = {}

    def commit_batch(self):
        with self._lock:
            self._last_fired.update(self._pending)
        self._pending = {}

    def rollback_batch(self):
        self._pending = {}


    def evaluate(self, conn, device: str, received_at: datetime, session_id: str, frame_id: int,
                 hits: list[Hit]) -> list[dict]:
        if not hits:
            return []
        by_label: dict[str, list[Hit]] = {}
        for h in hits:
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
                "frame_id": frame_id, "label": top.label if rule.match == "contains" else rule.label,
                "count": len(candidates), "max_confidence": top.confidence, "severity": rule.severity,
                "raised_at": received_at,
            })
        return raised

    # time comes from when the scheduler recieved the frame not from now
    # otherwise a backlog after an outage fires only one alert
    def _cooldown_expired(self, conn, rule: Rule, device: str, at: datetime) -> bool:
        key = (rule.id, device)
        last = self._pending.get(key)
        if last is None:
            with self._lock:
                last = self._last_fired.get(key)
        if last is None:
            last = conn.execute(
                select(func.max(alert.c.raised_at))
                .where(alert.c.rule_id == rule.id, alert.c.device_id == device)
            ).scalar() or _NEVER
            with self._lock:
                self._last_fired.setdefault(key, last)
        if last is _NEVER:
            return True
        return at - last >= timedelta(seconds=rule.cooldown_seconds)
