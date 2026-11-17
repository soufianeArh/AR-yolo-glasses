"""Configuration from environment variables (same defaults as the Java scheduler)."""
import os
import socket

DB_URL = os.getenv("AR_DB_URL", "postgresql+psycopg://ar:ar@127.0.0.1:5432/ar")
REDIS_URL = os.getenv("AR_REDIS_URL", "redis://127.0.0.1:6379/0")
STREAM = os.getenv("AR_STREAM", "ar:results")
GROUP = os.getenv("AR_GROUP", "control")
CONSUMER = os.getenv("AR_CONSUMER", f"{socket.gethostname()}-{os.getpid()}")
BATCH = int(os.getenv("AR_BATCH", "200"))
RULES_RELOAD_SECONDS = float(os.getenv("AR_RULES_RELOAD_SECONDS", "5"))
