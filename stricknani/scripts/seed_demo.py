"""Seed demo data for development."""

import asyncio
import shutil
from pathlib import Path

from sqlalchemy import select

from stricknani.config import config
from stricknani.database import AsyncSessionLocal, init_db
from stricknani.models import Image, ImageType, Project, ProjectCategory, Step
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

        # Demo assets directory
        demo_assets_dir = Path(__file__).parent.parent.parent / "demo_assets"

        # Create some demo projects
        demo_projects = [
            {
                "name": "Baby Blanket",
                "category": ProjectCategory.SCHAL.value,
                "yarn": "Soft Baby Yarn 100g",
                "needles": "4.0mm",
                "gauge_stitches": 22,
                "gauge_rows": 28,
                "comment": "Started this for my nephew!",
                "title_images": ["demo_image_1.jpg"],
                "steps": [
                    {
                        "title": "Cast On",
                        "description": (
                            "Cast on 120 stitches using the long-tail cast-on method."
                        ),
                        "images": ["demo_image_4.jpg"],
                    },
                    {
                        "title": "Knit Garter Stitch",
                        "description": (
                            "Knit every row for 100 rows. "
                            "This creates the garter stitch pattern."
                        ),
                        "images": ["demo_image_5.jpg"],
                    },
                ],
            },
            {
                "name": "Winter Scarf",
                "category": ProjectCategory.SCHAL.value,
                "yarn": "Merino Wool 200g",
                "needles": "5.0mm",
                "gauge_stitches": 18,
                "gauge_rows": 24,
                "title_images": ["demo_image_2.jpg"],
                "steps": [
                    {
                        "title": "Start Ribbing",
                        "description": (
                            "Work in 2x2 rib pattern: K2, P2 repeat across row."
                        ),
                        "images": ["demo_image_6.jpg"],
                    },
                ],
            },
            {
                "name": "Spring Pullover",
                "category": ProjectCategory.PULLOVER.value,
                "yarn": "Cotton Blend 400g",
                "needles": "3.5mm",
                "gauge_stitches": 24,
                "gauge_rows": 32,
                "comment": "Following a pattern from my favorite knitting book",
                "title_images": ["demo_image_3.jpg"],
                "steps": [],
            },
        ]

        for project_data in demo_projects:
            # Extract image and step data
            title_images = project_data.pop("title_images", [])
            steps_data = project_data.pop("steps", [])

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
                await db.flush()  # Get project ID

                # Copy and add title images
                for img_filename in title_images:
                    src_path = demo_assets_dir / img_filename
                    if src_path.exists():
                        # Create project media directory
                        project_media_dir = (
                            config.MEDIA_ROOT / "projects" / str(project.id)
                        )
                        project_media_dir.mkdir(parents=True, exist_ok=True)

                        # Copy image
                        dst_path = project_media_dir / img_filename
                        shutil.copy2(src_path, dst_path)

                        # Create image record
                        image = Image(
                            filename=img_filename,
                            original_filename=img_filename,
                            image_type=ImageType.PHOTO.value,
                            alt_text=f"{project.name} title image",
                            is_title_image=True,
                            project_id=project.id,
                        )
                        db.add(image)

                # Add steps
                for step_number, step_data in enumerate(steps_data, 1):
                    step_images = step_data.pop("images", [])
                    step = Step(
                        title=step_data["title"],
                        description=step_data.get("description"),
                        step_number=step_number,
                        project_id=project.id,
                    )
                    db.add(step)
                    await db.flush()  # Get step ID

                    # Copy and add step images
                    for img_filename in step_images:
                        src_path = demo_assets_dir / img_filename
                        if src_path.exists():
                            # Use same project media directory
                            project_media_dir = (
                                config.MEDIA_ROOT / "projects" / str(project.id)
                            )
                            project_media_dir.mkdir(parents=True, exist_ok=True)

                            # Copy image
                            dst_path = project_media_dir / img_filename
                            shutil.copy2(src_path, dst_path)

                            # Create image record
                            image = Image(
                                filename=img_filename,
                                original_filename=img_filename,
                                image_type=ImageType.PHOTO.value,
                                alt_text=f"{project.name} {step.title}",
                                is_title_image=False,
                                project_id=project.id,
                                step_id=step.id,
                            )
                            db.add(image)

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
