import sqlalchemy as sa
from alembic import op

revision = "0001"
down_revision = None
branch_labels = None
depends_on = None


# frames detections alerts and rules in one go
def upgrade():
    op.create_table(
        "frame",
        sa.Column("id", sa.BigInteger, primary_key=True),
        sa.Column("session_id", sa.String, nullable=False),
        sa.Column("frame_id", sa.BigInteger, nullable=False),
        sa.Column("device_id", sa.String, nullable=False),
        sa.Column("status", sa.String, nullable=False),
        sa.Column("worker_id", sa.String, nullable=False),
        sa.Column("infer_ms", sa.Integer, nullable=False),
        sa.Column("backend_ms", sa.Integer, nullable=False),
        sa.Column("error", sa.String, nullable=False),
        sa.Column("received_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("stored_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        sa.UniqueConstraint("session_id", "frame_id", name="uq_frame_session_frame"),
    )
    op.create_index("ix_frame_received_at", "frame", ["received_at"])
    op.create_index("ix_frame_device_received", "frame", ["device_id", "received_at"])

    op.create_table(
        "detection",
        sa.Column("id", sa.BigInteger, primary_key=True),
        sa.Column("frame_pk", sa.BigInteger, sa.ForeignKey("frame.id", ondelete="CASCADE"), nullable=False),
        sa.Column("label", sa.String, nullable=False),
        sa.Column("confidence", sa.Float, nullable=False),
        sa.Column("x1", sa.Float, nullable=False),
        sa.Column("y1", sa.Float, nullable=False),
        sa.Column("x2", sa.Float, nullable=False),
        sa.Column("y2", sa.Float, nullable=False),
        sa.Column("model", sa.String, nullable=False),
    )
    op.create_index("ix_detection_frame_pk", "detection", ["frame_pk"])
    op.create_index("ix_detection_label", "detection", ["label"])

    op.create_table(
        "alert_rule",
        sa.Column("id", sa.Integer, primary_key=True),
        sa.Column("name", sa.String, nullable=False, unique=True),
        sa.Column("label", sa.String, nullable=False),
        sa.Column("min_confidence", sa.Float, nullable=False),
        sa.Column("min_count", sa.Integer, nullable=False),
        sa.Column("cooldown_seconds", sa.Integer, nullable=False),
        sa.Column("severity", sa.String, nullable=False),
        sa.Column("device_id", sa.String, nullable=True),
        sa.Column("enabled", sa.Boolean, nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        sa.CheckConstraint("severity IN ('low', 'medium', 'high')", name="ck_alert_rule_severity"),
        sa.CheckConstraint("min_confidence >= 0 AND min_confidence <= 1", name="ck_alert_rule_confidence"),
        sa.CheckConstraint("min_count >= 1", name="ck_alert_rule_count"),
        sa.CheckConstraint("cooldown_seconds >= 0", name="ck_alert_rule_cooldown"),
    )

    op.create_table(
        "alert",
        sa.Column("id", sa.BigInteger, primary_key=True),
        sa.Column("rule_id", sa.Integer, sa.ForeignKey("alert_rule.id", ondelete="SET NULL"), nullable=True),
        sa.Column("rule_name", sa.String, nullable=False),
        sa.Column("device_id", sa.String, nullable=False),
        sa.Column("session_id", sa.String, nullable=False),
        sa.Column("frame_id", sa.BigInteger, nullable=False),
        sa.Column("label", sa.String, nullable=False),
        sa.Column("count", sa.Integer, nullable=False),
        sa.Column("max_confidence", sa.Float, nullable=False),
        sa.Column("severity", sa.String, nullable=False),
        sa.Column("raised_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("acknowledged", sa.Boolean, nullable=False, server_default=sa.false()),
        sa.Column("acknowledged_at", sa.DateTime(timezone=True), nullable=True),
    )
    op.create_index("ix_alert_rule_device_raised", "alert", ["rule_id", "device_id", "raised_at"])
    op.create_index("ix_alert_raised_at", "alert", ["raised_at"])


def downgrade():
    op.drop_table("alert")
    op.drop_table("alert_rule")
    op.drop_table("detection")
    op.drop_table("frame")
