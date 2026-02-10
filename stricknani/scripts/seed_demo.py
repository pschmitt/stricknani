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
DEMO_IMAGE_CAPTIONS = {
    # Yarn 1 (Sapphire Blue) & Project 1 (Blanket)
    "demo_image_1.jpg": "Skeins of vibrant sapphire blue merino wool on a wooden table",
    "demo_image_2.jpg": "Close-up of blue yarn texture and bamboo needles",
    "demo_image_3.jpg": "Finished blue baby blanket folded on a nursery chair",
    # Yarn 2 (Neon Rainbow) & Project 2 (Scarf)
    "demo_image_4.jpg": "Bright neon rainbow hand-dyed sock yarn skeins",
    "demo_image_5.jpg": "Winding a ball of colorful variegated yarn",
    "demo_image_6.jpg": "Ribbed scarf showing vibrant rainbow color transitions",
    "demo_image_19.jpg": (
        "Commuter rib scarf in deep red laid out on a neutral backdrop"
    ),
    # Yarn 3 (Ruby Red/Burgundy) & Project 3 (Beanie)
    "demo_image_7.jpg": "Chunky ruby red wool yarn balls",
    "demo_image_8.jpg": "Detail of thick red single-ply yarn twist",
    "demo_image_9.jpg": "Red bulky beanie with pom-pom on snowy background",
    # Yarn 4 (Golden Yellow) & Project 4 (Tee)
    "demo_image_10.jpg": "Skeins of golden yellow linen yarn with wildflowers",
    "demo_image_11.jpg": "Yellow linen fabric swatch on white background",
    "demo_image_12.jpg": "Summer tee in golden linen hanging on a clothesline",
    # Yarn 5 (Soft Pink) & Project 5 (Headband)
    "demo_image_13.jpg": "Delicate pale pink mohair silk yarn balls",
    "demo_image_14.jpg": "Pink mohair halo texture caught in sunlight",
    "demo_image_15.jpg": "Twisted headband in soft pink lace weight yarn",
    # Yarn 6 (Emerald Green) & Project 6 (Cardigan)
    "demo_image_16.jpg": "Dark emerald green tweed yarn with flecks",
    "demo_image_17.jpg": "Rustic green tweed swatch with wooden buttons",
    "demo_image_18.jpg": "Cozy green cardigan laid out on a bed",
}


def _demo_caption(filename: str, fallback: str) -> str:
    return DEMO_IMAGE_CAPTIONS.get(filename, fallback)


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
                "name": "Riverbend Merino DK",
                "brand": "North Ridge Wool",
                "colorway": "Sapphire Blue",
                "fiber_content": "100% Merino Wool",
                "weight_category": "DK",
                "weight_grams": 100,
                "length_meters": 220,
                "recommended_needles": "3.75mm - 4.5mm",
                "description": (
                    "Springy 4-ply merino in a deep, rich sapphire blue. "
                    "Crisp definition."
                ),
                "notes": (
                    "Swatches to gauge quickly and relaxes slightly after wet blocking."
                ),
                "link": "https://example.com/yarns/northridge-riverbend-merino-dk",
                "photos": ["demo_image_1.jpg", "demo_image_2.jpg"],
            },
            {
                "name": "Laneway Sock 4ply",
                "brand": "Harbor Mill",
                "colorway": "Neon Prism",
                "fiber_content": "75% Wool, 25% Nylon",
                "weight_category": "Fingering",
                "weight_grams": 100,
                "length_meters": 400,
                "recommended_needles": "2.0mm - 2.75mm",
                "description": (
                    "Vibrant hand-dyed sock yarn with bright neon rainbow speckles."
                ),
                "notes": "Best for socks at 2.25mm and marled accessories at 3mm.",
                "link": "https://example.com/yarns/harbor-mill-laneway-sock-4ply",
                "photos": ["demo_image_4.jpg", "demo_image_5.jpg"],
            },
            {
                "name": "Summit Bulky",
                "brand": "Timberline Fibers",
                "colorway": "Ruby Red",
                "fiber_content": "50% Wool, 50% Acrylic",
                "weight_category": "Bulky",
                "weight_grams": 200,
                "length_meters": 150,
                "recommended_needles": "6.0mm - 8.0mm",
                "description": (
                    "A deep, warm burgundy red bulky yarn. Soft and quick to knit."
                ),
                "notes": "Responds well to steam; avoid over-handling to keep loft.",
                "link": "https://example.com/yarns/timberline-summit-bulky",
                "photos": ["demo_image_7.jpg", "demo_image_8.jpg"],
            },
            {
                "name": "Coastal Linen Sport",
                "brand": "Drift Thread Co.",
                "colorway": "Goldenrod",
                "fiber_content": "60% Linen, 40% Cotton",
                "weight_category": "Sport",
                "weight_grams": 100,
                "length_meters": 300,
                "recommended_needles": "3.0mm - 3.75mm",
                "description": (
                    "Sunny golden yellow linen blend that softens beautifully "
                    "with wear."
                ),
                "notes": "Works well for warm-weather pullovers and lightweight tees.",
                "link": "https://example.com/yarns/drift-thread-coastal-linen-sport",
                "photos": ["demo_image_10.jpg", "demo_image_11.jpg"],
            },
            {
                "name": "Halo Alpaca Silk",
                "brand": "Andes Loft",
                "colorway": "Petal Pink",
                "fiber_content": "70% Alpaca, 30% Silk",
                "weight_category": "Lace",
                "weight_grams": 50,
                "length_meters": 400,
                "recommended_needles": "2.5mm - 3.5mm",
                "description": (
                    "Soft, airy lace weight in a delicate pale pink. Wonderful halo."
                ),
                "notes": "Great for softening cables and adding warmth without bulk.",
                "link": "https://example.com/yarns/andes-loft-halo-alpaca-silk",
                "photos": ["demo_image_13.jpg", "demo_image_14.jpg"],
            },
            {
                "name": "Highland Tweed Worsted",
                "brand": "North Ridge",
                "colorway": "Emerald Forest",
                "fiber_content": "100% Wool",
                "weight_category": "Worsted",
                "weight_grams": 100,
                "length_meters": 200,
                "recommended_needles": "4.5mm - 5.0mm",
                "description": (
                    "Deep green tweed with contrasting flecks. Classic and sturdy."
                ),
                "notes": "Hard-wearing choice for cardigans, mittens, and hats.",
                "link": "https://example.com/yarns/northridge-highland-tweed-worsted",
                "photos": ["demo_image_16.jpg", "demo_image_17.jpg"],
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
            existing_yarn = result.scalar_one_or_none()

            if not existing_yarn:
                yarn = Yarn(**yarn_data, owner_id=demo_user.id)
                db.add(yarn)
                await db.flush()  # Get yarn ID

                # Copy and add photos
                for i, img_filename in enumerate(photos):
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
                            alt_text=_demo_caption(
                                img_filename, f"{yarn.name} yarn photo"
                            ),
                            yarn_id=yarn.id,
                            is_primary=(i == 0),
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

        yarn_result = await db.execute(
            select(Yarn).where(Yarn.owner_id == demo_user.id)
        )
        yarn_by_name = {yarn.name: yarn for yarn in yarn_result.scalars().all()}

        # Create some demo projects
        demo_projects = [
            {
                "name": "Heirloom Baby Blanket",
                "category": ProjectCategory.SCHAL.value,
                "yarn": "Riverbend Merino DK 300g",
                "needles": "4.0mm",
                "description": (
                    "A squishy garter-and-eyelet blanket in sapphire blue."
                ),
                "notes": (
                    "Knitting this as a family gift; adding a sewn label after "
                    "blocking."
                ),
                "tags": json.dumps(["gift", "garter", "baby", "beginner-friendly"]),
                "link": "https://example.com/patterns/heirloom-baby-blanket",
                "linked_yarns": ["Riverbend Merino DK"],
                "title_images": [
                    "demo_image_3.jpg",  # Finished blanket
                    "demo_image_1.jpg",  # Yarn
                    "demo_image_2.jpg",  # Process
                ],
                "steps": [
                    {
                        "title": "Cast On Border",
                        "description": (
                            "Cast on 126 stitches using long-tail and work 8 "
                            "garter rows for a stable edge."
                        ),
                        "images": ["demo_image_2.jpg"],
                    },
                    {
                        "title": "Body Repeat",
                        "description": (
                            "Repeat 10 rows garter + 2 rows eyelets until length "
                            "reaches 82 cm."
                        ),
                        "images": ["demo_image_3.jpg"],
                    },
                ],
            },
            {
                "name": "Commuter Rib Scarf",
                "category": ProjectCategory.SCHAL.value,
                # Keep this demo project tied to the rainbow yarn variant.
                "yarn": "Laneway Sock 4ply 200g",
                "needles": "3.5mm",
                "description": (
                    "Vibrant rainbow ribbed scarf to brighten up winter days."
                ),
                "notes": "The variegation pools nicely in the 2x2 ribbing.",
                "tags": json.dumps(["ribbing", "winter", "rainbow", "unisex"]),
                "link": "https://example.com/patterns/commuter-rib-scarf",
                "linked_yarns": ["Laneway Sock 4ply"],
                "title_images": [
                    "demo_image_19.jpg",  # Finished Scarf (updated main image)
                    "demo_image_4.jpg",  # Yarn
                    "demo_image_5.jpg",  # Process
                ],
                "steps": [
                    {
                        "title": "Set Up Rib",
                        "description": (
                            "Work 2x2 ribbing, slipping first stitch for clean "
                            "selvedges."
                        ),
                        "images": ["demo_image_5.jpg"],
                    },
                    {
                        "title": "Length and Finish",
                        "description": (
                            "Continue rib to 180 cm, then bind off in pattern and "
                            "soak block."
                        ),
                        "images": ["demo_image_19.jpg"],
                    },
                ],
            },
            {
                "name": "Seabreeze Raglan Tee",
                "category": ProjectCategory.PULLOVER.value,
                "yarn": "Coastal Linen Sport 420g",
                "needles": "3.5mm",
                "description": ("Top-down summer raglan in golden yellow linen."),
                "notes": "Trying short-row shaping at the back neck for better fit.",
                "tags": json.dumps(["summer", "raglan", "lightweight", "top-down"]),
                "link": "https://example.com/patterns/seabreeze-raglan-tee",
                "linked_yarns": ["Coastal Linen Sport"],
                "title_images": [
                    "demo_image_12.jpg",  # Finished Tee
                    "demo_image_10.jpg",  # Yarn
                    "demo_image_11.jpg",  # Process
                ],
                "steps": [
                    {
                        "title": "Ribbed Hem",
                        "description": (
                            "Work 5 cm of 1x1 twisted rib for the hem and body edge."
                        ),
                        "images": ["demo_image_11.jpg"],
                    },
                    {
                        "title": "Raglan Increases",
                        "description": (
                            "Increase every other round at four raglan markers to "
                            "chest fit."
                        ),
                        "images": ["demo_image_12.jpg"],
                    },
                ],
            },
            {
                "name": "City Lights Beanie",
                "category": ProjectCategory.MUTZE.value,
                "yarn": "Summit Bulky 110g",
                "needles": "7.0mm",
                "description": ("A quick ruby red beanie with folded brim."),
                "notes": "Made this as a same-day gift before a weekend trip.",
                "tags": json.dumps(["quick", "gift", "bulky", "one-skein"]),
                "link": "https://example.com/patterns/city-lights-beanie",
                "linked_yarns": ["Summit Bulky"],
                "title_images": ["demo_image_9.jpg", "demo_image_7.jpg"],
                "steps": [
                    {
                        "title": "Twisted Rib Brim",
                        "description": (
                            "Work Ktbl/P1 rib for 8 cm, then fold brim to desired "
                            "depth."
                        ),
                        "images": ["demo_image_8.jpg"],
                    },
                    {
                        "title": "Crown Shaping",
                        "description": (
                            "Decrease in 8 sections every other round to 8 "
                            "stitches, then close."
                        ),
                        "images": ["demo_image_9.jpg"],
                    },
                ],
            },
            {
                "name": "Twisted Halo Headband",
                "category": ProjectCategory.STIRNBAND.value,
                "yarn": "Halo Alpaca Silk",
                # Keep this project pink-themed and avoid rainbow yarn photos.
                "needles": "3.25mm",
                "description": ("Lightweight pink mohair headband with a front twist."),
                "notes": (
                    "Holding two strands gave better stitch visibility than lace alone."
                ),
                "tags": json.dumps(["lace", "lightweight", "halo", "accessory"]),
                "link": "https://example.com/patterns/twisted-halo-headband",
                "linked_yarns": ["Halo Alpaca Silk"],
                "title_images": ["demo_image_15.jpg", "demo_image_13.jpg"],
                "steps": [
                    {
                        "title": "Lace Panel",
                        "description": (
                            "Follow chart for 12 repeats; keep edges in garter."
                        ),
                        "images": ["demo_image_14.jpg"],
                    },
                    {
                        "title": "Twist Join",
                        "description": "Join ends with a simple twist and seam.",
                        "images": ["demo_image_15.jpg"],
                    },
                ],
            },
            {
                "name": "Cabin Weekend Cardigan",
                "category": ProjectCategory.JACKE.value,
                "yarn": "Highland Tweed Worsted 760g",
                "needles": "5.0mm",
                "description": ("Forest green tweed cardigan with pockets."),
                "notes": "Adding reinforced elbow patches after first wear test.",
                "tags": json.dumps(["cardigan", "pockets", "tweed", "outerwear"]),
                "link": "https://example.com/patterns/cabin-weekend-cardigan",
                "linked_yarns": ["Highland Tweed Worsted"],
                "title_images": [
                    "demo_image_18.jpg",  # Finished
                    "demo_image_16.jpg",  # Yarn
                    "demo_image_17.jpg",  # Process
                ],
                "steps": [
                    {
                        "title": "Back Panel",
                        "description": "Work flat until 45 cm, then shape shoulders.",
                        "images": ["demo_image_17.jpg"],
                    },
                    {
                        "title": "Pick Up Sleeves",
                        "description": (
                            "Pick up around armholes and knit sleeves in the round."
                        ),
                        "images": ["demo_image_18.jpg"],
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
            from typing import cast

            linked_yarns = cast(list[str], project_data.pop("linked_yarns", []))
            if not isinstance(linked_yarns, list):
                linked_yarns = []

            # Check if project already exists
            project_result = await db.execute(
                select(Project).where(
                    Project.name == project_data["name"],
                    Project.owner_id == demo_user.id,
                )
            )
            existing_project = project_result.scalar_one_or_none()

            project: Project | None = existing_project
            if project is None:
                project = Project(**project_data, owner_id=demo_user.id)
                db.add(project)
                await db.flush()  # Get project ID

                # Copy and add title images
                for i, img_filename in enumerate(title_images):
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
                            alt_text=_demo_caption(
                                img_filename,
                                f"{project.name} title image",
                            ),
                            is_title_image=(i == 0),
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
                                alt_text=_demo_caption(
                                    img_filename,
                                    f"{project.name} {step.title}",
                                ),
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

            if project is None:
                continue

            if linked_yarns:
                await db.refresh(project, ["yarns"])
                for yarn_name in linked_yarns:
                    linked_yarn = yarn_by_name.get(yarn_name)
                    if linked_yarn and linked_yarn not in project.yarns:
                        project.yarns.append(linked_yarn)

        project_result = await db.execute(
            select(Project).where(Project.owner_id == demo_user.id)
        )
        project_by_name = {
            project.name: project for project in project_result.scalars().all()
        }

        await db.refresh(demo_user, ["favorite_projects", "favorite_yarns"])

        favorite_project_names = [
            "Commuter Rib Scarf",
            "Cabin Weekend Cardigan",
            "Twisted Halo Headband",
        ]
        for project_name in favorite_project_names:
            favorite_project = project_by_name.get(project_name)
            if favorite_project and favorite_project not in demo_user.favorite_projects:
                demo_user.favorite_projects.append(favorite_project)

        favorite_yarn_names = [
            "Highland Tweed Worsted",
            "Halo Alpaca Silk",
            "Laneway Sock 4ply",
        ]
        for yarn_name in favorite_yarn_names:
            favorite_yarn = yarn_by_name.get(yarn_name)
            if favorite_yarn and favorite_yarn not in demo_user.favorite_yarns:
                demo_user.favorite_yarns.append(favorite_yarn)

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
