"""
Shared test setup. Uses REAL Postgres (database ar_test) and REAL Redis (database 15),
never the normal 'ar' database. Every test starts from empty tables.
"""
import os
import uuid

os.environ.setdefault("AR_DB_URL", "postgresql+psycopg://ar:ar@127.0.0.1:5432/ar_test")
os.environ.setdefault("AR_REDIS_URL", "redis://127.0.0.1:6379/15")
os.environ.setdefault("AR_RULES_RELOAD_SECONDS", "0")
assert os.environ["AR_DB_URL"].endswith("/ar_test"), "tests must never run against the real database"

import pytest  # noqa: E402
from alembic import command  # noqa: E402
from alembic.config import Config  # noqa: E402
from sqlalchemy import text  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)


def alembic_config():
    cfg = Config(os.path.join(ROOT, "alembic.ini"))
    cfg.set_main_option("script_location", os.path.join(ROOT, "migrations"))
    return cfg


@pytest.fixture(scope="session", autouse=True)
def schema():
    """Migrations down to nothing and back up: tests the downgrade path too."""
    cfg = alembic_config()
    command.downgrade(cfg, "base")
    command.upgrade(cfg, "head")
    yield


@pytest.fixture()
def engine(schema):
    from ar_control.db import make_engine
    eng = make_engine()
    with eng.begin() as c:
        c.execute(text("TRUNCATE alert, alert_rule, detection, frame RESTART IDENTITY CASCADE"))
    yield eng
    eng.dispose()


@pytest.fixture()
def stream():
    """A fresh Redis stream name per test, deleted afterwards."""
    import redis
    r = redis.Redis.from_url(os.environ["AR_REDIS_URL"])
    name = f"ar:test:{uuid.uuid4().hex[:8]}"
    yield r, name
    r.delete(name)
