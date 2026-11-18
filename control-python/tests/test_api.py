from datetime import datetime, timezone

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import insert

from ar_control.api import app
from ar_control.db import alert, detection, frame


@pytest.fixture()
def client(engine):
    return TestClient(app)


RULE = {"name": "cows", "label": "Cow", "min_confidence": 0.6, "min_count": 2, "cooldown_seconds": 30,
        "severity": "high"}


# full round trip create read patch delete
def test_rule_crud(client):
    r = client.post("/rules", json=RULE)
    assert r.status_code == 201
    rule = r.json()
    assert rule["label"] == "cow" and rule["enabled"] is True and rule["device_id"] is None
    assert client.get(f"/rules/{rule['id']}").json()["name"] == "cows"
    patched = client.patch(f"/rules/{rule['id']}", json={"cooldown_seconds": 5, "enabled": False}).json()
    assert patched["cooldown_seconds"] == 5 and patched["enabled"] is False and patched["min_count"] == 2
    assert [x["id"] for x in client.get("/rules").json()] == [rule["id"]]
    assert client.delete(f"/rules/{rule['id']}").status_code == 204
    assert client.get(f"/rules/{rule['id']}").status_code == 404
    assert client.delete(f"/rules/{rule['id']}").status_code == 404


def test_rule_validation(client):
    assert client.post("/rules", json={**RULE, "min_confidence": 1.5}).status_code == 422
    assert client.post("/rules", json={**RULE, "min_count": 0}).status_code == 422
    assert client.post("/rules", json={**RULE, "severity": "urgent"}).status_code == 422
    assert client.post("/rules", json={**RULE, "label": ""}).status_code == 422
    assert client.post("/rules", json=RULE).status_code == 201
    assert client.post("/rules", json=RULE).status_code == 409
    assert client.patch("/rules/1", json={}).status_code == 400
    assert client.patch("/rules/999", json={"enabled": False}).status_code == 404


def _seed(engine):
    now = datetime.now(timezone.utc)
    with engine.begin() as c:
        pk = c.execute(insert(frame).values(session_id="s1", frame_id=1, device_id="dev-a", status="OK",
                                            worker_id="worker-1", infer_ms=90, backend_ms=110, error="",
                                            received_at=now).returning(frame.c.id)).scalar()
        c.execute(insert(frame).values(session_id="s1", frame_id=2, device_id="dev-a", status="DROPPED_DEADLINE",
                                       worker_id="worker-2", infer_ms=0, backend_ms=300, error="late",
                                       received_at=now))
        c.execute(insert(detection), [
            {"frame_pk": pk, "label": "cow", "confidence": 0.9, "x1": 0, "y1": 0, "x2": 1, "y2": 1, "model": "default"},
            {"frame_pk": pk, "label": "bird", "confidence": 0.7, "x1": 0, "y1": 0, "x2": 1, "y2": 1, "model": "default"}])
        aid = c.execute(insert(alert).values(rule_id=None, rule_name="cows", device_id="dev-a", session_id="s1",
                                             frame_id=1, label="cow", count=1, max_confidence=0.9, severity="high",
                                             raised_at=now).returning(alert.c.id)).scalar()
    return aid


def test_alerts_and_ack_is_idempotent(client, engine):
    aid = _seed(engine)
    assert len(client.get("/alerts", params={"acknowledged": False}).json()) == 1
    first = client.post(f"/alerts/{aid}/ack").json()
    second = client.post(f"/alerts/{aid}/ack").json()
    assert first["acknowledged"] and second["acknowledged_at"] == first["acknowledged_at"]
    assert client.get("/alerts", params={"acknowledged": False}).json() == []
    assert client.post("/alerts/999/ack").status_code == 404
    s = client.get("/alerts/summary").json()
    assert s["total"] == 1 and s["unacknowledged"] == 0


def test_detections_and_stats(client, engine):
    _seed(engine)
    assert {d["label"] for d in client.get("/detections").json()} == {"cow", "bird"}
    assert [d["label"] for d in client.get("/detections", params={"label": "cow"}).json()] == ["cow"]
    assert client.get("/detections", params={"device_id": "dev-zzz"}).json() == []
    st = client.get("/stats").json()
    assert st["frames_by_status"] == {"OK": 1, "DROPPED_DEADLINE": 1}
    assert st["ok_by_worker"] == {"worker-1": 1} and st["top_labels"] == {"cow": 1, "bird": 1}
    assert st["backend_ms"]["p50"] == 110


def test_health(client):
    h = client.get("/health").json()
    assert h["database"] == "ok" and h["redis"] == "ok"


def test_contains_rule_through_the_api(client):
    r = client.post("/rules", json={"name": "any-blight", "label": "Blight", "match": "contains",
                                    "min_confidence": 0.5, "severity": "high"})
    assert r.status_code == 201 and r.json()["match"] == "contains" and r.json()["label"] == "blight"
    assert client.post("/rules", json={**RULE, "name": "x", "match": "regex"}).status_code == 422
    assert client.post("/rules", json={**RULE, "name": "y"}).json()["match"] == "exact"
    rid = r.json()["id"]
    assert client.patch(f"/rules/{rid}", json={"match": "exact"}).json()["match"] == "exact"
