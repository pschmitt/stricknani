"""Database session management."""

from collections.abc import AsyncGenerator

from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from stricknani.config import config

# Convert sqlite:/// to sqlite+aiosqlite:///
database_url = config.DATABASE_URL
if database_url.startswith("sqlite:"):
    database_url = database_url.replace("sqlite:", "sqlite+aiosqlite:")

engine = create_async_engine(database_url, echo=config.DEBUG)
AsyncSessionLocal = async_sessionmaker(
    engine, class_=AsyncSession, expire_on_commit=False
)


async def get_db() -> AsyncGenerator[AsyncSession]:
    """Get database session."""
    async with AsyncSessionLocal() as session:
        yield session


async def init_db() -> None:
    """Initialize the database."""
    from stricknani.models import Base

    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
