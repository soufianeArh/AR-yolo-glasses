from sqlalchemy import (BigInteger, Boolean, Column, DateTime, Float, ForeignKey, Integer, MetaData, String,
                        Table, UniqueConstraint, create_engine, func)

from . import settings

metadata = MetaData()

frame = Table(
    "frame", metadata,
    Column("id", BigInteger, primary_key=True),
    Column("session_id", String, nullable=False),
    Column("frame_id", BigInteger, nullable=False),
    Column("device_id", String, nullable=False),
    Column("status", String, nullable=False),
    Column("worker_id", String, nullable=False),
    Column("infer_ms", Integer, nullable=False),
    Column("backend_ms", Integer, nullable=False),
    Column("error", String, nullable=False),
    Column("received_at", DateTime(timezone=True), nullable=False),
    Column("stored_at", DateTime(timezone=True), nullable=False, server_default=func.now()),
    UniqueConstraint("session_id", "frame_id", name="uq_frame_session_frame"),
)

detection = Table(
    "detection", metadata,
    Column("id", BigInteger, primary_key=True),
    Column("frame_pk", BigInteger, ForeignKey("frame.id", ondelete="CASCADE"), nullable=False),
    Column("label", String, nullable=False),
    Column("confidence", Float, nullable=False),
    Column("x1", Float, nullable=False),
    Column("y1", Float, nullable=False),
    Column("x2", Float, nullable=False),
    Column("y2", Float, nullable=False),
    Column("model", String, nullable=False),
)

alert_rule = Table(
    "alert_rule", metadata,
    Column("id", Integer, primary_key=True),
    Column("name", String, nullable=False, unique=True),
    Column("label", String, nullable=False),
    Column("match", String, nullable=False, server_default="exact"),
    Column("min_confidence", Float, nullable=False),
    Column("min_count", Integer, nullable=False),
    Column("cooldown_seconds", Integer, nullable=False),
    Column("severity", String, nullable=False),
    Column("device_id", String, nullable=True),
    Column("enabled", Boolean, nullable=False),
    Column("created_at", DateTime(timezone=True), nullable=False, server_default=func.now()),
    Column("updated_at", DateTime(timezone=True), nullable=False, server_default=func.now()),
)

alert = Table(
    "alert", metadata,
    Column("id", BigInteger, primary_key=True),
    Column("rule_id", Integer, ForeignKey("alert_rule.id", ondelete="SET NULL"), nullable=True),
    Column("rule_name", String, nullable=False),
    Column("device_id", String, nullable=False),
    Column("session_id", String, nullable=False),
    Column("frame_id", BigInteger, nullable=False),
    Column("label", String, nullable=False),
    Column("count", Integer, nullable=False),
    Column("max_confidence", Float, nullable=False),
    Column("severity", String, nullable=False),
    Column("raised_at", DateTime(timezone=True), nullable=False),
    Column("acknowledged", Boolean, nullable=False, server_default="false"),
    Column("acknowledged_at", DateTime(timezone=True), nullable=True),
)


# one engine per process
def make_engine(url=None):
    return create_engine(url or settings.DB_URL, pool_pre_ping=True, future=True)
