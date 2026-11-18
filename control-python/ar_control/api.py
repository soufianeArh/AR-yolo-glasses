"""
REST API of the control plane: history, alerts, rules, health and stats, and the demo videos
for the Android app (Step 8.9, ar_control/demos.py).

  ~/ar-venv/bin/uvicorn ar_control.api:app --host 0.0.0.0 --port 8090

A separate process from the consumer, on purpose: a slow API request can never delay the
storage of results, and the consumer can be restarted without taking the API down.
"""
from datetime import datetime, timedelta, timezone
from typing import Literal

import redis
from fastapi import Depends, FastAPI, HTTPException, Query
from pydantic import BaseModel, Field, field_validator
from sqlalchemy import and_, delete, func, insert, select, text, update
from sqlalchemy.exc import IntegrityError

from . import settings
from .db import alert, alert_rule, detection, frame, make_engine
from .demos import router as demos_router

app = FastAPI(title="AR control plane", version="0.1.0")
app.include_router(demos_router)
engine = make_engine()
_redis = redis.Redis.from_url(settings.REDIS_URL, socket_timeout=1, socket_connect_timeout=1)

Severity = Literal["low", "medium", "high"]
Match = Literal["exact", "contains"]


def conn():
    with engine.begin() as c:
        yield c


def _row(r) -> dict:
    return {k: (v.isoformat() if isinstance(v, datetime) else v) for k, v in dict(r).items()}


# ------------------------------------------------------------------ rules

class RuleIn(BaseModel):
    name: str = Field(min_length=1, max_length=100)
    label: str = Field(min_length=1, max_length=100, description="model class, e.g. 'cow' (case-insensitive)")
    match: Match = Field("exact", description="'exact' label, or 'contains' (e.g. 'blight' covers every blight)")
    min_confidence: float = Field(0.5, ge=0, le=1)
    min_count: int = Field(1, ge=1)
    cooldown_seconds: int = Field(60, ge=0)
    severity: Severity = "medium"
    device_id: str | None = Field(None, description="only this device; null = any device")
    enabled: bool = True

    @field_validator("label")
    @classmethod
    def lower_label(cls, v: str) -> str:
        return v.strip().lower()


class RulePatch(BaseModel):
    name: str | None = Field(None, min_length=1, max_length=100)
    label: str | None = Field(None, min_length=1, max_length=100)
    match: Match | None = None
    min_confidence: float | None = Field(None, ge=0, le=1)
    min_count: int | None = Field(None, ge=1)
    cooldown_seconds: int | None = Field(None, ge=0)
    severity: Severity | None = None
    device_id: str | None = None
    enabled: bool | None = None

    @field_validator("label")
    @classmethod
    def lower_label(cls, v):
        return v.strip().lower() if v is not None else v


@app.get("/rules")
def list_rules(c=Depends(conn)):
    return [_row(r) for r in c.execute(select(alert_rule).order_by(alert_rule.c.id)).mappings()]


@app.post("/rules", status_code=201)
def create_rule(body: RuleIn, c=Depends(conn)):
    try:
        r = c.execute(insert(alert_rule).values(**body.model_dump()).returning(alert_rule)).mappings().one()
    except IntegrityError:
        raise HTTPException(409, f"a rule named '{body.name}' already exists")
    return _row(r)


@app.get("/rules/{rule_id}")
def get_rule(rule_id: int, c=Depends(conn)):
    r = c.execute(select(alert_rule).where(alert_rule.c.id == rule_id)).mappings().first()
    if r is None:
        raise HTTPException(404, f"no rule {rule_id}")
    return _row(r)


@app.patch("/rules/{rule_id}")
def update_rule(rule_id: int, body: RulePatch, c=Depends(conn)):
    changes = body.model_dump(exclude_unset=True)
    if not changes:
        raise HTTPException(400, "nothing to change")
    try:
        r = c.execute(update(alert_rule).where(alert_rule.c.id == rule_id)
                      .values(**changes, updated_at=func.now()).returning(alert_rule)).mappings().first()
    except IntegrityError:
        raise HTTPException(409, f"a rule named '{changes.get('name')}' already exists")
    if r is None:
        raise HTTPException(404, f"no rule {rule_id}")
    return _row(r)


@app.delete("/rules/{rule_id}", status_code=204)
def delete_rule(rule_id: int, c=Depends(conn)):
    # Past alerts stay: their rule_id becomes NULL, rule_name keeps the name.
    if c.execute(delete(alert_rule).where(alert_rule.c.id == rule_id)).rowcount == 0:
        raise HTTPException(404, f"no rule {rule_id}")


# ------------------------------------------------------------------ alerts

@app.get("/alerts")
def list_alerts(device_id: str | None = None, severity: Severity | None = None, acknowledged: bool | None = None,
                limit: int = Query(100, ge=1, le=500), c=Depends(conn)):
    q = select(alert).order_by(alert.c.raised_at.desc(), alert.c.id.desc()).limit(limit)
    if device_id is not None:
        q = q.where(alert.c.device_id == device_id)
    if severity is not None:
        q = q.where(alert.c.severity == severity)
    if acknowledged is not None:
        q = q.where(alert.c.acknowledged.is_(acknowledged))
    return [_row(r) for r in c.execute(q).mappings()]


@app.post("/alerts/{alert_id}/ack")
def ack_alert(alert_id: int, c=Depends(conn)):
    """Idempotent: acknowledging twice keeps the first acknowledgement time."""
    r = c.execute(update(alert).where(alert.c.id == alert_id, alert.c.acknowledged.is_(False))
                  .values(acknowledged=True, acknowledged_at=func.now()).returning(alert)).mappings().first()
    if r is None:
        r = c.execute(select(alert).where(alert.c.id == alert_id)).mappings().first()
        if r is None:
            raise HTTPException(404, f"no alert {alert_id}")
    return _row(r)


@app.get("/alerts/summary")
def alerts_summary(c=Depends(conn)):
    rows = c.execute(select(alert.c.severity, func.count(), func.count().filter(alert.c.acknowledged.is_(False)))
                     .group_by(alert.c.severity)).all()
    return {"by_severity": {s: {"total": t, "unacknowledged": u} for s, t, u in rows},
            "total": sum(t for _, t, _ in rows), "unacknowledged": sum(u for _, _, u in rows)}


# ------------------------------------------------------------------ history

@app.get("/detections")
def list_detections(device_id: str | None = None, label: str | None = None,
                    minutes: int = Query(60, ge=1, le=7 * 24 * 60), limit: int = Query(100, ge=1, le=500),
                    c=Depends(conn)):
    since = datetime.now(timezone.utc) - timedelta(minutes=minutes)
    q = (select(detection.c.label, detection.c.confidence, detection.c.x1, detection.c.y1, detection.c.x2,
                detection.c.y2, detection.c.model, frame.c.device_id, frame.c.session_id, frame.c.frame_id,
                frame.c.worker_id, frame.c.received_at)
         .join(frame, detection.c.frame_pk == frame.c.id)
         .where(frame.c.received_at >= since)
         .order_by(frame.c.received_at.desc(), detection.c.id).limit(limit))
    if device_id is not None:
        q = q.where(frame.c.device_id == device_id)
    if label is not None:
        q = q.where(detection.c.label == label)
    return [_row(r) for r in c.execute(q).mappings()]


@app.get("/stats")
def stats(minutes: int = Query(5, ge=1, le=24 * 60), c=Depends(conn)):
    """Frames per status, OK latency percentiles and per-worker share, over the last N minutes."""
    since = datetime.now(timezone.utc) - timedelta(minutes=minutes)
    by_status = dict(c.execute(select(frame.c.status, func.count()).where(frame.c.received_at >= since)
                               .group_by(frame.c.status)).all())
    pct = c.execute(text("""
        SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY backend_ms),
               percentile_cont(0.95) WITHIN GROUP (ORDER BY backend_ms),
               percentile_cont(0.99) WITHIN GROUP (ORDER BY backend_ms)
        FROM frame WHERE status = 'OK' AND received_at >= :since"""), {"since": since}).one()
    by_worker = dict(c.execute(select(frame.c.worker_id, func.count())
                               .where(and_(frame.c.received_at >= since, frame.c.status == "OK"))
                               .group_by(frame.c.worker_id)).all())
    top = c.execute(select(detection.c.label, func.count()).join(frame, detection.c.frame_pk == frame.c.id)
                    .where(frame.c.received_at >= since).group_by(detection.c.label)
                    .order_by(func.count().desc()).limit(10)).all()
    return {"minutes": minutes, "frames_by_status": by_status, "frames": sum(by_status.values()),
            "backend_ms": {"p50": pct[0], "p95": pct[1], "p99": pct[2]},
            "ok_by_worker": by_worker, "top_labels": dict(top)}


# ------------------------------------------------------------------ health

@app.get("/health")
def health():
    """Database + Redis reachability and how far behind the consumer is."""
    out = {"database": "ok", "redis": "ok", "stream": settings.STREAM, "consumer_group": settings.GROUP}
    try:
        with engine.connect() as c:
            c.execute(text("SELECT 1"))
    except Exception as e:
        out["database"] = f"down: {type(e).__name__}"
    try:
        out["stream_length"] = _redis.xlen(settings.STREAM)
        groups = {g["name"].decode(): g for g in _redis.xinfo_groups(settings.STREAM)}
        g = groups.get(settings.GROUP)
        if g is None:
            out["consumer"] = "no consumer group yet"
        else:
            out["pending"] = g["pending"]          # delivered, not yet acknowledged
            out["lag"] = g.get("lag")              # in the stream, not yet delivered
    except redis.ResponseError:
        out["consumer"] = "stream does not exist yet"
    except Exception as e:
        out["redis"] = f"down: {type(e).__name__}"
    out["ok"] = out["database"] == "ok" and out["redis"] == "ok"
    return out
