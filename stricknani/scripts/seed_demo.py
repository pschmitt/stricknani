"""Seed demo data for development."""

import argparse
import asyncio
import json
import shutil
from pathlib import Path

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from stricknani.config import config
from stricknani.database import AsyncSessionLocal, init_db
from stricknani.models import (
    Image,
    ImageType,
    Project,
    ProjectCategory,
    Step,
    User,
    Yarn,
    YarnImage,
)
from stricknani.utils.auth import create_user, get_user_by_email

_THUMBNAILS_AVAILABLE: bool | None = None


async def _reset_demo_data(db: AsyncSession, demo_user: User) -> None:
    await db.refresh(
        demo_user,
        ["projects", "yarns", "favorite_projects", "favorite_yarns", "categories"],
    )

    demo_user.favorite_projects.clear()
    demo_user.favorite_yarns.clear()

    for project in list(demo_user.projects):
        project_media_dir = config.MEDIA_ROOT / "projects" / str(project.id)
        if project_media_dir.exists():
            shutil.rmtree(project_media_dir)
        await db.delete(project)

    for yarn in list(demo_user.yarns):
        yarn_media_dir = config.MEDIA_ROOT / "yarns" / str(yarn.id)
        if yarn_media_dir.exists():
            shutil.rmtree(yarn_media_dir)
        await db.delete(yarn)

    for category in list(demo_user.categories):
        await db.delete(category)

    await db.commit()


async def _maybe_create_thumbnail(
    source_path: Path, entity_id: int, subdir: str
) -> None:
    global _THUMBNAILS_AVAILABLE

    if _THUMBNAILS_AVAILABLE is False:
        return

    try:
        from stricknani.utils.files import create_thumbnail
    except ModuleNotFoundError:
        if _THUMBNAILS_AVAILABLE is None:
            print("Pillow not installed; skipping thumbnail generation.")
        _THUMBNAILS_AVAILABLE = False
        return

    _THUMBNAILS_AVAILABLE = True
    try:
        await create_thumbnail(source_path, entity_id, subdir=subdir)
    except Exception as exc:  # pragma: no cover - demo utility
        print(f"Failed to create thumbnail for {source_path.name}: {exc}")


async def _ensure_thumbnails(db: AsyncSession, demo_user: User) -> None:
    await db.refresh(demo_user, ["projects", "yarns"])

    for project in demo_user.projects:
        await db.refresh(project, ["images"])
        for image in project.images:
            source_path = (
                config.MEDIA_ROOT / "projects" / str(project.id) / image.filename
            )
            thumb_path = (
                config.MEDIA_ROOT
                / "thumbnails"
                / "projects"
                / str(project.id)
                / f"thumb_{Path(image.filename).stem}.jpg"
            )
            if source_path.exists() and not thumb_path.exists():
                await _maybe_create_thumbnail(
                    source_path, project.id, subdir="projects"
                )

    for yarn in demo_user.yarns:
        await db.refresh(yarn, ["photos"])
        for photo in yarn.photos:
            source_path = config.MEDIA_ROOT / "yarns" / str(yarn.id) / photo.filename
            thumb_path = (
                config.MEDIA_ROOT
                / "thumbnails"
                / "yarns"
                / str(yarn.id)
                / f"thumb_{Path(photo.filename).stem}.jpg"
            )
            if source_path.exists() and not thumb_path.exists():
                await _maybe_create_thumbnail(source_path, yarn.id, subdir="yarns")


async def seed_demo_data(reset: bool = False) -> None:
    """Seed demo data into the database."""
    async with AsyncSessionLocal() as db:
        # Check if demo user exists
        demo_user = await get_user_by_email(db, "demo@stricknani.local")

        if not demo_user:
            print("Creating demo user: demo@stricknani.local / demo")
            demo_user = await create_user(
                db, "demo@stricknani.local", "demo", is_admin=True
            )
        else:
            print("Demo user already exists")
            # Make sure demo user is admin
            if not demo_user.is_admin:
                demo_user.is_admin = True
                db.add(demo_user)
                await db.commit()
                print("Updated demo user to admin")

        if reset:
            print("Resetting demo data...")
            await _reset_demo_data(db, demo_user)

        # Demo assets directory
        demo_assets_dir = Path(__file__).parent.parent.parent / "demo_assets"

        # Create some demo yarns
        demo_yarns = [
            {
                "name": "Merino Soft",
                "brand": "CozyKnits",
                "colorway": "Ocean Blue",
                "fiber_content": "100% Merino Wool",
                "weight_category": "DK",
                "weight_grams": 100,
                "length_meters": 220,
                "recommended_needles": "3.75mm - 4.5mm",
                "description": "Soft, bouncy merino with a smooth twist.",
                "notes": "Super soft, perfect for baby clothes.",
                "link": "https://example.com/yarns/cozyknits-merino-soft",
                "photos": ["demo_image_1.jpg", "demo_image_7.jpg"],
            },
            {
                "name": "Sock Delight",
                "brand": "HappyFeet",
                "colorway": "Rainbow",
                "fiber_content": "75% Wool, 25% Nylon",
                "weight_category": "Fingering",
                "weight_grams": 100,
                "length_meters": 400,
                "recommended_needles": "2.0mm - 2.75mm",
                "description": "Tightly spun sock yarn with gentle striping.",
                "notes": "Self-striping yarn.",
                "link": "https://example.com/yarns/happyfeet-sock-delight",
                "photos": ["demo_image_2.jpg", "demo_image_8.jpg"],
            },
            {
                "name": "Chunky Monkey",
                "brand": "BigYarns",
                "colorway": "Charcoal",
                "fiber_content": "50% Wool, 50% Acrylic",
                "weight_category": "Bulky",
                "weight_grams": 200,
                "length_meters": 150,
                "recommended_needles": "6.0mm - 8.0mm",
                "description": "A quick-knit bulky blend with a soft halo.",
                "notes": "Great for quick hats and scarves.",
                "link": "https://example.com/yarns/bigyarns-chunky-monkey",
                "photos": ["demo_image_3.jpg", "demo_image_12.jpg"],
            },
            {
                "name": "Linen Breeze",
                "brand": "Coastline Fibers",
                "colorway": "Natural Flax",
                "fiber_content": "60% Linen, 40% Cotton",
                "weight_category": "Sport",
                "weight_grams": 100,
                "length_meters": 300,
                "recommended_needles": "3.0mm - 3.75mm",
                "description": "Crisp plant-fiber blend that softens after washing.",
                "notes": "Lovely stitch definition for summer tops.",
                "link": "https://example.com/yarns/coastline-linen-breeze",
                "photos": ["demo_image_4.jpg", "demo_image_9.jpg"],
            },
            {
                "name": "Alpaca Cloud",
                "brand": "Andes Loft",
                "colorway": "Mushroom",
                "fiber_content": "70% Alpaca, 30% Silk",
                "weight_category": "Lace",
                "weight_grams": 50,
                "length_meters": 400,
                "recommended_needles": "2.5mm - 3.5mm",
                "description": "Featherlight with a soft sheen.",
                "notes": "Pairs well with a fingering base for texture.",
                "link": "https://example.com/yarns/andes-alpaca-cloud",
                "photos": ["demo_image_5.jpg", "demo_image_11.jpg"],
            },
            {
                "name": "Highland Tweed",
                "brand": "North Ridge",
                "colorway": "Forest",
                "fiber_content": "100% Wool",
                "weight_category": "Worsted",
                "weight_grams": 100,
                "length_meters": 200,
                "recommended_needles": "4.5mm - 5.0mm",
                "description": "Hearty tweed with subtle color flecks.",
                "notes": "Perfect for cozy sweaters and cardigans.",
                "link": "https://example.com/yarns/northridge-highland-tweed",
                "photos": ["demo_image_6.jpg", "demo_image_10.jpg"],
            },
        ]

        for yarn_data in demo_yarns:
            photos = yarn_data.pop("photos", [])
            if not isinstance(photos, list):
                photos = []

            # Check if yarn already exists
            result = await db.execute(
                select(Yarn).where(
                    Yarn.name == yarn_data["name"],
                    Yarn.owner_id == demo_user.id,
                )
            )
            existing = result.scalar_one_or_none()

            if not existing:
                yarn = Yarn(**yarn_data, owner_id=demo_user.id)
                db.add(yarn)
                await db.flush()  # Get yarn ID

                # Copy and add photos
                for img_filename in photos:
                    src_path = demo_assets_dir / img_filename
                    if src_path.exists():
                        # Create yarn media directory
                        yarn_media_dir = config.MEDIA_ROOT / "yarns" / str(yarn.id)
                        yarn_media_dir.mkdir(parents=True, exist_ok=True)

                        # Copy image
                        dst_path = yarn_media_dir / img_filename
                        shutil.copy2(src_path, dst_path)

                        # Create image record
                        yarn_image = YarnImage(
                            filename=img_filename,
                            original_filename=img_filename,
                            alt_text=f"{yarn.name} photo",
                            yarn_id=yarn.id,
                        )
                        db.add(yarn_image)
                        await _maybe_create_thumbnail(
                            dst_path,
                            yarn.id,
                            subdir="yarns",
                        )

                print(f"Created yarn: {yarn_data['name']}")
            else:
                print(f"Yarn already exists: {yarn_data['name']}")

        result = await db.execute(select(Yarn).where(Yarn.owner_id == demo_user.id))
        yarn_by_name = {yarn.name: yarn for yarn in result.scalars().all()}

        # Create some demo projects
        demo_projects = [
            {
                "name": "Baby Blanket",
                "category": ProjectCategory.SCHAL.value,
                "yarn": "Soft Baby Yarn 100g",
                "needles": "4.0mm",
                "recommended_needles": "4.0mm",
                "gauge_stitches": 22,
                "gauge_rows": 28,
                "comment": "Started this for my nephew. Keeping it simple and squishy.",
                "tags": json.dumps(["gift", "garter", "baby"]),
                "link": "https://example.com/patterns/baby-blanket",
                "linked_yarns": ["Merino Soft"],
                "title_images": [
                    "demo_image_1.jpg",
                    "demo_image_4.jpg",
                    "demo_image_7.jpg",
                ],
                "steps": [
                    {
                        "title": "Cast On",
                        "description": (
                            "Cast on 120 stitches using the long-tail cast-on method."
                        ),
                        "images": ["demo_image_4.jpg", "demo_image_9.jpg"],
                    },
                    {
                        "title": "Knit Garter Stitch",
                        "description": (
                            "Knit every row for 100 rows. "
                            "This creates the garter stitch pattern."
                        ),
                        "images": ["demo_image_5.jpg", "demo_image_7.jpg"],
                    },
                    {
                        "title": "Bind Off",
                        "description": (
                            "Bind off loosely and steam-block to square the edges."
                        ),
                        "images": ["demo_image_6.jpg", "demo_image_10.jpg"],
                    },
                ],
            },
            {
                "name": "Winter Scarf",
                "category": ProjectCategory.SCHAL.value,
                "yarn": "Highland Tweed 200g",
                "needles": "5.0mm",
                "recommended_needles": "5.0mm",
                "gauge_stitches": 18,
                "gauge_rows": 24,
                "comment": "Deep ribbing for a warm, structured drape.",
                "tags": json.dumps(["ribbing", "winter", "tweed"]),
                "link": "https://example.com/patterns/winter-scarf",
                "linked_yarns": ["Highland Tweed"],
                "title_images": [
                    "demo_image_2.jpg",
                    "demo_image_6.jpg",
                    "demo_image_8.jpg",
                ],
                "steps": [
                    {
                        "title": "Start Ribbing",
                        "description": (
                            "Work in 2x2 rib pattern: K2, P2 repeat across row."
                        ),
                        "images": ["demo_image_6.jpg", "demo_image_10.jpg"],
                    },
                    {
                        "title": "Switch to Seed Stitch",
                        "description": (
                            "After 20 cm, switch to seed stitch for texture."
                        ),
                        "images": ["demo_image_3.jpg", "demo_image_12.jpg"],
                    },
                ],
            },
            {
                "name": "Spring Pullover",
                "category": ProjectCategory.PULLOVER.value,
                "yarn": "Linen Breeze 400g",
                "needles": "3.5mm",
                "recommended_needles": "3.5mm",
                "gauge_stitches": 24,
                "gauge_rows": 32,
                "comment": "Following a pattern from my favorite knitting book.",
                "tags": json.dumps(["summer", "raglan", "lightweight"]),
                "link": "https://example.com/patterns/spring-pullover",
                "linked_yarns": ["Linen Breeze"],
                "title_images": [
                    "demo_image_3.jpg",
                    "demo_image_4.jpg",
                    "demo_image_9.jpg",
                ],
                "steps": [
                    {
                        "title": "Ribbed Hem",
                        "description": "Work 6 cm of 1x1 ribbing in the round.",
                        "images": ["demo_image_4.jpg", "demo_image_9.jpg"],
                    },
                    {
                        "title": "Raglan Increases",
                        "description": (
                            "Increase every other round until yoke fits comfortably."
                        ),
                        "images": ["demo_image_1.jpg", "demo_image_7.jpg"],
                    },
                ],
            },
            {
                "name": "City Beanie",
                "category": ProjectCategory.MUTZE.value,
                "yarn": "Chunky Monkey 100g",
                "needles": "7.0mm",
                "recommended_needles": "7.0mm",
                "gauge_stitches": 14,
                "gauge_rows": 20,
                "comment": "Quick knit for chilly commutes.",
                "tags": json.dumps(["quick", "gift", "bulky"]),
                "link": "https://example.com/patterns/city-beanie",
                "linked_yarns": ["Chunky Monkey"],
                "title_images": ["demo_image_3.jpg", "demo_image_12.jpg"],
                "steps": [
                    {
                        "title": "Twisted Rib Brim",
                        "description": "Ktbl, P1 ribbing for 7 cm.",
                        "images": ["demo_image_3.jpg", "demo_image_12.jpg"],
                    },
                    {
                        "title": "Crown Shaping",
                        "description": (
                            "Decrease every 3rd round until 8 stitches remain."
                        ),
                        "images": ["demo_image_2.jpg", "demo_image_8.jpg"],
                    },
                ],
            },
            {
                "name": "Lace Headband",
                "category": ProjectCategory.STIRNBAND.value,
                "yarn": "Alpaca Cloud held with Sock Delight",
                "needles": "3.25mm",
                "recommended_needles": "3.25mm",
                "gauge_stitches": 26,
                "gauge_rows": 34,
                "comment": "Lightweight lace for shoulder-season walks.",
                "tags": json.dumps(["lace", "lightweight"]),
                "link": "https://example.com/patterns/lace-headband",
                "linked_yarns": ["Alpaca Cloud", "Sock Delight"],
                "title_images": ["demo_image_5.jpg", "demo_image_11.jpg"],
                "steps": [
                    {
                        "title": "Lace Panel",
                        "description": (
                            "Follow chart for 12 repeats; keep edges in garter."
                        ),
                        "images": ["demo_image_5.jpg", "demo_image_11.jpg"],
                    },
                    {
                        "title": "Twist Join",
                        "description": "Join ends with a simple twist and seam.",
                        "images": ["demo_image_1.jpg", "demo_image_7.jpg"],
                    },
                ],
            },
            {
                "name": "Weekend Cardigan",
                "category": ProjectCategory.JACKE.value,
                "yarn": "Highland Tweed 700g",
                "needles": "5.0mm",
                "recommended_needles": "5.0mm",
                "gauge_stitches": 18,
                "gauge_rows": 26,
                "comment": "Cozy layers with roomy pockets.",
                "tags": json.dumps(["cardigan", "pockets", "tweed"]),
                "link": "https://example.com/patterns/weekend-cardigan",
                "linked_yarns": ["Highland Tweed"],
                "title_images": [
                    "demo_image_6.jpg",
                    "demo_image_2.jpg",
                    "demo_image_10.jpg",
                ],
                "steps": [
                    {
                        "title": "Back Panel",
                        "description": "Work flat until 45 cm, then shape shoulders.",
                        "images": ["demo_image_6.jpg", "demo_image_10.jpg"],
                    },
                    {
                        "title": "Pick Up Sleeves",
                        "description": (
                            "Pick up around armholes and knit sleeves in the round."
                        ),
                        "images": ["demo_image_2.jpg", "demo_image_8.jpg"],
                    },
                ],
            },
        ]

        for project_data in demo_projects:
            # Extract image and step data
            title_images = project_data.pop("title_images", [])
            if not isinstance(title_images, list):
                title_images = []
            steps_data = project_data.pop("steps", [])
            if not isinstance(steps_data, list):
                steps_data = []
            linked_yarns = project_data.pop("linked_yarns", [])
            if not isinstance(linked_yarns, list):
                linked_yarns = []

            # Check if project already exists
            result = await db.execute(
                select(Project).where(
                    Project.name == project_data["name"],
                    Project.owner_id == demo_user.id,
                )
            )
            existing = result.scalar_one_or_none()

            project = existing
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
                        project_image = Image(
                            filename=img_filename,
                            original_filename=img_filename,
                            image_type=ImageType.PHOTO.value,
                            alt_text=f"{project.name} title image",
                            is_title_image=True,
                            project_id=project.id,
                        )
                        db.add(project_image)
                        await _maybe_create_thumbnail(
                            dst_path,
                            project.id,
                            subdir="projects",
                        )

                # Add steps
                for step_number, step_data in enumerate(steps_data, 1):
                    if not isinstance(step_data, dict):
                        continue
                    step_images = step_data.pop("images", [])
                    if not isinstance(step_images, list):
                        step_images = []
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
                            step_image = Image(
                                filename=img_filename,
                                original_filename=img_filename,
                                image_type=ImageType.PHOTO.value,
                                alt_text=f"{project.name} {step.title}",
                                is_title_image=False,
                                project_id=project.id,
                                step_id=step.id,
                            )
                            db.add(step_image)
                            await _maybe_create_thumbnail(
                                dst_path,
                                project.id,
                                subdir="projects",
                            )

                print(f"Created project: {project_data['name']}")
            else:
                print(f"Project already exists: {project_data['name']}")

            if project and linked_yarns:
                await db.refresh(project, ["yarns"])
                for yarn_name in linked_yarns:
                    yarn = yarn_by_name.get(yarn_name)
                    if yarn and yarn not in project.yarns:
                        project.yarns.append(yarn)

        result = await db.execute(
            select(Project).where(Project.owner_id == demo_user.id)
        )
        project_by_name = {project.name: project for project in result.scalars().all()}

        await db.refresh(demo_user, ["favorite_projects", "favorite_yarns"])

        favorite_project_names = [
            "Winter Scarf",
            "Weekend Cardigan",
            "Lace Headband",
        ]
        for project_name in favorite_project_names:
            project = project_by_name.get(project_name)
            if project and project not in demo_user.favorite_projects:
                demo_user.favorite_projects.append(project)

        favorite_yarn_names = [
            "Highland Tweed",
            "Alpaca Cloud",
            "Sock Delight",
        ]
        for yarn_name in favorite_yarn_names:
            yarn = yarn_by_name.get(yarn_name)
            if yarn and yarn not in demo_user.favorite_yarns:
                demo_user.favorite_yarns.append(yarn)

        await _ensure_thumbnails(db, demo_user)

        await db.commit()

    print("\nDemo data seeded successfully!")
    print("Login with: demo@stricknani.local / demo")


async def main(reset: bool = False) -> None:
    """Main entry point."""
    print("Initializing database...")
    await init_db()
    print("Database initialized.")

    print("\nSeeding demo data...")
    await seed_demo_data(reset=reset)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Seed Stricknani demo data.")
    parser.add_argument(
        "--reset",
        action="store_true",
        help="Delete existing demo user data before seeding.",
    )
    args = parser.parse_args()
    asyncio.run(main(reset=args.reset))
