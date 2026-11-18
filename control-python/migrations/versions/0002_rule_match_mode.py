import sqlalchemy as sa
from alembic import op

revision = "0002"
down_revision = "0001"
branch_labels = None
depends_on = None


# contains mode lets one rule cover every blight
def upgrade():
    op.add_column("alert_rule", sa.Column("match", sa.String, nullable=False, server_default="exact"))
    op.create_check_constraint("ck_alert_rule_match", "alert_rule", "match IN ('exact', 'contains')")


def downgrade():
    op.drop_constraint("ck_alert_rule_match", "alert_rule", type_="check")
    op.drop_column("alert_rule", "match")
