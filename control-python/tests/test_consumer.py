import os
import time

from sqlalchemy import func, insert, select

from ar_control.consumer import Consumer, process_batch
from ar_control.contract import Detection, FrameResult, ResultStatus
from ar_control.db import alert, alert_rule, detection, frame
from ar_control.rules import RuleEngine


def entry(frame_id, status=ResultStatus.OK, labels=("cow",), device="dev-a", session="s-test-1", ms=None):
    r = FrameResult(session_id=session, frame_id=frame_id, status=status, worker_id="worker-1",
                    infer_ms=100, backend_ms=120)
    if status == ResultStatus.OK:
        for lab in labels:
            r.detections.append(Detection(label=lab, confidence=0.9, x1=0.1, y1=0.2, x2=0.3, y2=0.4,
                                          model="default"))
    return {b"pb": r.SerializeToString(), b"device": device.encode(),
            b"received_ms": str(ms if ms is not None else int(time.time() * 1000)).encode()}


def count(engine, table):
    with engine.connect() as c:
        return c.execute(select(func.count()).select_from(table)).scalar()


def consumer(name, stream_name):
    return Consumer(redis_url=os.environ["AR_REDIS_URL"], stream=stream_name, group="test", name=name,
                    rules_reload_seconds=0)


# redis and postgres are real here and not mocked
def test_frames_and_detections_are_stored(engine):
    entries = [(b"1-0", entry(1, labels=("cow", "cow", "bird"))),
               (b"2-0", entry(2, status=ResultStatus.DROPPED_DEADLINE)),
               (b"3-0", entry(3, labels=()))]
    stats = process_batch(engine, RuleEngine(0), entries)
    assert stats["frames"] == 3 and stats["detections"] == 3
    assert count(engine, frame) == 3 and count(engine, detection) == 3
    with engine.connect() as c:
        statuses = sorted(c.execute(select(frame.c.status)).scalars())
    assert statuses == ["DROPPED_DEADLINE", "OK", "OK"]


def test_redelivered_entries_are_stored_once(engine):
    entries = [(b"1-0", entry(1)), (b"2-0", entry(2))]
    process_batch(engine, RuleEngine(0), entries)
    again = process_batch(engine, RuleEngine(0), entries)
    assert again["duplicates"] == 2 and again["frames"] == 0
    assert count(engine, frame) == 2 and count(engine, detection) == 2


def test_bad_entry_is_skipped_and_acknowledged(engine, stream):
    r, name = stream
    r.xadd(name, {b"pb": b"\xff\xfe not protobuf", b"device": b"x", b"received_ms": b"1"})
    r.xadd(name, entry(1))
    c = consumer("c1", name)
    assert c.run_once(block_ms=200) == 2
    assert c.totals["invalid"] == 1 and c.totals["frames"] == 1
    assert r.xpending(name, "test")["pending"] == 0


def test_crash_before_ack_is_recovered_on_restart(engine, stream):
    r, name = stream
    for i in range(5):
        r.xadd(name, entry(i))
    crashed = consumer("worker-a", name)
    crashed.ensure_group()
    delivered = crashed.read(pending=False, block_ms=200)
    assert len(delivered) == 5 and count(engine, frame) == 0
    assert r.xpending(name, "test")["pending"] == 5

    restarted = consumer("worker-a", name)
    assert restarted.run_once(block_ms=200) == 5
    assert count(engine, frame) == 5
    assert r.xpending(name, "test")["pending"] == 0


def test_alerts_raised_through_the_pipeline(engine, stream):
    with engine.begin() as c:
        c.execute(insert(alert_rule).values(name="cows", label="cow", min_confidence=0.5, min_count=1,
                                            cooldown_seconds=10, severity="high", device_id=None, enabled=True))
    r, name = stream
    t0 = 1_800_000_000_000
    for i in range(25):
        r.xadd(name, entry(i, ms=t0 + i * 1000))
    c = consumer("c1", name)
    while c.run_once(block_ms=200):
        pass
    assert c.totals["frames"] == 25
    assert count(engine, alert) == 3
