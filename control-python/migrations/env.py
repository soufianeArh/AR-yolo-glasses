from alembic import context
from sqlalchemy import create_engine

from ar_control import settings
from ar_control.db import metadata

config = context.config
target_metadata = metadata


def run_migrations_offline():
    context.configure(url=settings.DB_URL, target_metadata=target_metadata, literal_binds=True)
    with context.begin_transaction():
        context.run_migrations()


def run_migrations_online():
    engine = create_engine(settings.DB_URL)
    with engine.connect() as connection:
        context.configure(connection=connection, target_metadata=target_metadata)
        with context.begin_transaction():
            context.run_migrations()
    engine.dispose()


if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()
