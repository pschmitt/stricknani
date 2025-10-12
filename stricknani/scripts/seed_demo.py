"""Seed demo data for development."""

import asyncio

from sqlalchemy import select

from stricknani.database import AsyncSessionLocal, init_db
from stricknani.models import Project, ProjectCategory
from stricknani.utils.auth import create_user, get_user_by_email


async def seed_demo_data() -> None:
    """Seed demo data into the database."""
    async with AsyncSessionLocal() as db:
        # Check if demo user exists
        demo_user = await get_user_by_email(db, "demo@stricknani.local")

        if not demo_user:
            print("Creating demo user: demo@stricknani.local / demo")
            demo_user = await create_user(db, "demo@stricknani.local", "demo")
        else:
            print("Demo user already exists")

        # Create some demo projects
        demo_projects = [
            {
                "name": "Baby Blanket",
                "category": ProjectCategory.SCHAL.value,
                "yarn": "Soft Baby Yarn 100g",
                "needles": "4.0mm",
                "gauge_stitches": 22,
                "gauge_rows": 28,
                "instructions": "# Baby Blanket Pattern\n\n1. Cast on 120 stitches\n2. Knit in garter stitch for 100 rows\n3. Bind off",  # noqa: E501
                "comment": "Started this for my nephew!",
            },
            {
                "name": "Winter Scarf",
                "category": ProjectCategory.SCHAL.value,
                "yarn": "Merino Wool 200g",
                "needles": "5.0mm",
                "gauge_stitches": 18,
                "gauge_rows": 24,
                "instructions": "# Scarf Pattern\n\n- Cast on 40 stitches\n- Work in 2x2 rib\n- Continue until 150cm",  # noqa: E501
            },
            {
                "name": "Spring Pullover",
                "category": ProjectCategory.PULLOVER.value,
                "yarn": "Cotton Blend 400g",
                "needles": "3.5mm",
                "gauge_stitches": 24,
                "gauge_rows": 32,
                "comment": "Following a pattern from my favorite knitting book",
            },
        ]

        for project_data in demo_projects:
            # Check if project already exists
            result = await db.execute(
                select(Project).where(
                    Project.name == project_data["name"],
                    Project.owner_id == demo_user.id,
                )
            )
            existing = result.scalar_one_or_none()

            if not existing:
                project = Project(**project_data, owner_id=demo_user.id)
                db.add(project)
                print(f"Created project: {project_data['name']}")
            else:
                print(f"Project already exists: {project_data['name']}")

        await db.commit()

    print("\nDemo data seeded successfully!")
    print("Login with: demo@stricknani.local / demo")


async def main() -> None:
    """Main entry point."""
    print("Initializing database...")
    await init_db()
    print("Database initialized.")

    print("\nSeeding demo data...")
    await seed_demo_data()


if __name__ == "__main__":
    asyncio.run(main())
