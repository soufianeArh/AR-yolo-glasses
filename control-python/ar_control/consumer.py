import logging
import signal
import time
from datetime import datetime, timezone

import redis
from sqlalchemy import insert
from sqlalchemy.dialects.postgresql import insert as pg_insert

from . import settings
from .contract import FrameResult, ResultStatus, STATUS_NAME
from .db import alert, detection, frame, make_engine
from .rules import Hit, RuleEngine

log = logging.getLogger("ar_control.consumer")


class Stop(Exception):
    pass


def decode(fields: dict) -> tuple:
    try:
        result = FrameResult.FromString(fields[b"pb"])
        device = fields[b"device"].decode()
        received_ms = int(fields[b"received_ms"])
    except Exception as e:
        raise ValueError(f"bad stream entry: {e}") from e
    return result, device, datetime.fromtimestamp(received_ms / 1000, tz=timezone.utc)


def process_batch(engine, rules: RuleEngine, entries: list) -> dict:
    stats = {"frames": 0, "duplicates": 0, "detections": 0, "alerts": 0, "invalid": 0}
    rules.begin_batch()
    try:
        with engine.begin() as conn:
            for _entry_id, fields in entries:
                try:
                    r, device, received_at = decode(fields)
                except ValueError as e:
                    stats["invalid"] += 1
                    log.warning("%s", e)
                    continue
                pk = conn.execute(
                    pg_insert(frame).values(
                        session_id=r.session_id, frame_id=r.frame_id, device_id=device,
                        status=STATUS_NAME.get(r.status, str(r.status)), worker_id=r.worker_id,
                        infer_ms=r.infer_ms, backend_ms=r.backend_ms, error=r.error, received_at=received_at)
                    .on_conflict_do_nothing(constraint="uq_frame_session_frame")
                    .returning(frame.c.id)
                ).scalar()
                if pk is None:
                    stats["duplicates"] += 1
                    continue
                stats["frames"] += 1
                if r.status != ResultStatus.OK or not r.detections:
                    continue
                conn.execute(insert(detection), [
                    {"frame_pk": pk, "label": d.label, "confidence": d.confidence, "x1": d.x1, "y1": d.y1,
                     "x2": d.x2, "y2": d.y2, "model": d.model} for d in r.detections])
                stats["detections"] += len(r.detections)
                raised = rules.evaluate(conn, device, received_at, r.session_id, r.frame_id,
                                        [Hit(d.label, d.confidence) for d in r.detections])
                if raised:
                    conn.execute(insert(alert), raised)
                    stats["alerts"] += len(raised)
                    for a in raised:
                        log.info("ALERT [%s] %s: %s x%d on %s (conf %.2f)", a["severity"], a["rule_name"],
                                 a["label"], a["count"], device, a["max_confidence"])
    except Exception:
        rules.rollback_batch()
        raise
    rules.commit_batch()
    return stats


class Consumer:
    def __init__(self, redis_url=None, db_url=None, stream=None, group=None, name=None, batch=None,
                 rules_reload_seconds=None):
        self.r = redis.Redis.from_url(redis_url or settings.REDIS_URL)
        self.engine = make_engine(db_url)
        self.stream = stream or settings.STREAM
        self.group = group or settings.GROUP
        self.name = name or settings.CONSUMER
        self.batch = batch or settings.BATCH
        self.rules = RuleEngine(settings.RULES_RELOAD_SECONDS if rules_reload_seconds is None
                                else rules_reload_seconds)
        self.totals = {"frames": 0, "duplicates": 0, "detections": 0, "alerts": 0, "invalid": 0}
        self.stopping = False

    def ensure_group(self):
        try:
            self.r.xgroup_create(self.stream, self.group, id="0", mkstream=True)
            log.info("created consumer group '%s' on stream '%s'", self.group, self.stream)
        except redis.ResponseError as e:
            # the group already being there is fine
            if "BUSYGROUP" not in str(e):
                raise

    def read(self, pending: bool, block_ms: int):
        resp = self.r.xreadgroup(self.group, self.name, {self.stream: "0" if pending else ">"},
                                 count=self.batch, block=None if pending else block_ms)
        return resp[0][1] if resp else []

    def handle(self, entries) -> bool:
        if not entries:
            return True
        try:
            stats = process_batch(self.engine, self.rules, entries)
        except Exception as e:
            log.error("database error, batch of %d kept pending, will retry: %s", len(entries), e)
            return False
        # ack only after the commit so nothing is lost if we crash in between
        self.r.xack(self.stream,self.group, *[eid for eid, _ in entries])
        for k, v in stats.items():
            self.totals[k] += v
        return True

    def run_once(self, block_ms=1000) -> int:
        self.ensure_group()
        entries = self.read(pending=True, block_ms=0) or self.read(pending=False, block_ms=block_ms)
        return len(entries) if self.handle(entries) else 0

    def run(self):
        backoff = 0.5
        last_log = time.monotonic()
        recovering = True
        connected = None
        while not self.stopping:
            try:
                self.ensure_group()
                entries = self.read(pending=recovering, block_ms=1000)
                if recovering and not entries:
                    recovering = False
                    continue
                if connected is not True:
                    log.info("redis reachable, consuming stream '%s' as '%s' in group '%s'",
                             self.stream, self.name, self.group)
                    connected = True
                if not self.handle(entries):
                    recovering = True
                    time.sleep(backoff)
                    backoff = min(backoff * 2, 5)
                    continue
                backoff = 0.5
            except redis.ResponseError as e:
                if "NOGROUP" in str(e):
                    log.warning("consumer group missing (Redis restarted?), recreating")
                    recovering = True
                    continue
                raise
            except redis.ConnectionError as e:
                if connected is not False:
                    log.warning("redis unreachable (%s), retrying", e)
                    connected = False
                time.sleep(backoff)
                backoff = min(backoff * 2, 5)
                recovering = True
            if time.monotonic() - last_log >= 10:
                log.info("totals: %s", self.totals)
                last_log = time.monotonic()
        log.info("stopped. totals: %s", self.totals)


def main():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s.%(msecs)03d [consumer] %(message)s",
                        datefmt="%H:%M:%S")
    c = Consumer()

    def stop(*_):
        c.stopping = True

    signal.signal(signal.SIGINT, stop)
    signal.signal(signal.SIGTERM, stop)
    log.info("READY stream '%s', group '%s', consumer '%s'", c.stream, c.group, c.name)
    c.run()


if __name__ == "__main__":
    main()
