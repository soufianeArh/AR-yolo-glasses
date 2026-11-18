"""alert_rule.match: 'exact' (default, as before) or 'contains'

With the plant-disease model (Step 7) one rule must cover several labels, e.g. "blight" for
Corn leaf blight, Tomato leaf late blight, Potato leaf early blight...

Revision ID: 0002
Revises: 0001
Create Date: 2026-11-17
"""
import sqlalchemy as sa
from alembic import op

revision = "0002"
down_revision = "0001"
branch_labels = None
depends_on = None


def upgrade():
    op.add_column("alert_rule", sa.Column("match", sa.String, nullable=False, server_default="exact"))
    op.create_check_constraint("ck_alert_rule_match", "alert_rule", "match IN ('exact', 'contains')")


def downgrade():
    op.drop_constraint("ck_alert_rule_match", "alert_rule", type_="check")
    op.drop_column("alert_rule", "match")
