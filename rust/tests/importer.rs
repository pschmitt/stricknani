use std::fs;
use std::path::{Path, PathBuf};

use image::{DynamicImage, ImageFormat, Rgba, RgbaImage};
use sha2::{Digest, Sha256};
use sqlx::sqlite::{SqliteConnectOptions, SqlitePoolOptions};
use sqlx::{Row, SqlitePool};
use stricknani_server::importer::{ImportError, ImportOptions, import_snapshot};
use tempfile::TempDir;

const FIXTURE_SQL: &str = include_str!("fixtures/legacy_snapshot.sql");
const PDF_BYTES: &[u8] = b"%PDF-1.4\n1 0 obj\n<<>>\nendobj\n%%EOF\n";

struct Fixture {
    directory: TempDir,
    source_db: PathBuf,
    source_media: PathBuf,
    destination: PathBuf,
}

impl Fixture {
    async fn new() -> Self {
        let directory = tempfile::tempdir().expect("fixture directory is created");
        let source_db = directory.path().join("legacy.sqlite3");
        let source_media = directory.path().join("legacy-media");
        let destination = directory.path().join("rust-data");
        fs::create_dir(&source_media).expect("source media root is created");
        let png = valid_png();

        let pool = source_pool(&source_db).await;
        sqlx::raw_sql(FIXTURE_SQL)
            .execute(&pool)
            .await
            .expect("legacy fixture schema and data are created");
        sqlx::query("UPDATE attachments SET size_bytes = ? WHERE id = 1")
            .bind(png.len() as i64)
            .execute(&pool)
            .await
            .expect("fixture image attachment size is accurate");
        sqlx::query("UPDATE attachments SET size_bytes = ? WHERE id = 2")
            .bind(PDF_BYTES.len() as i64)
            .execute(&pool)
            .await
            .expect("fixture PDF size is accurate");
        pool.close().await;

        write_source_file(&source_media, "projects", 19, "shared.png", &png);
        write_source_file(&source_media, "projects", 19, "title-a.png", &png);
        write_source_file(&source_media, "projects", 19, "title-b.png", &png);
        write_source_file(&source_media, "projects", 19, "document.pdf", PDF_BYTES);
        write_source_file(&source_media, "yarns", 17, "photo.png", &png);
        write_source_file(&source_media, "users", 7, "avatar.png", &png);

        Self {
            directory,
            source_db,
            source_media,
            destination,
        }
    }

    fn options(&self) -> ImportOptions {
        ImportOptions {
            source_db: self.source_db.clone(),
            source_media: self.source_media.clone(),
            destination: self.destination.clone(),
        }
    }
}

#[tokio::test]
async fn imports_all_legacy_records_and_media_without_mutating_the_snapshot() {
    let fixture = Fixture::new().await;
    let db_before = hash_file(&fixture.source_db);
    let media_before = hash_tree(&fixture.source_media);

    let report = import_snapshot(fixture.options())
        .await
        .expect("valid copied snapshot is imported");

    assert_eq!(report.users, 1);
    assert_eq!(report.api_tokens, 1);
    assert_eq!(report.categories, 2);
    assert_eq!(report.synthesized_categories, 1);
    assert_eq!(report.yarns, 1);
    assert_eq!(report.projects, 2);
    assert_eq!(report.project_steps, 1);
    assert_eq!(report.project_yarns, 1);
    assert_eq!(report.project_favorites, 1);
    assert_eq!(report.yarn_favorites, 1);
    assert_eq!(report.project_images, 3);
    assert_eq!(report.attachments, 2);
    assert_eq!(report.yarn_images, 1);
    assert_eq!(report.user_avatars, 1);
    assert_eq!(report.audit_events, 3);
    assert_eq!(report.deletion_events, 1);
    assert_eq!(report.reused_media_aliases, 1);

    let target = target_pool(&fixture.destination).await;
    let user =
        sqlx::query("SELECT email,legacy_email,password_hash,active,is_admin,created_at,profile_image,legacy_token_version FROM users WHERE id=7")
            .fetch_one(&target)
            .await
            .expect("account is imported with its identity");
    assert_eq!(user.get::<String, _>("email"), "fixture@example.invalid");
    assert_eq!(
        user.get::<String, _>("legacy_email"),
        "fixture@example.invalid"
    );
    assert_eq!(
        user.get::<String, _>("password_hash"),
        "not-a-real-password-hash-fixture"
    );
    assert_eq!(user.get::<i64, _>("active"), 1);
    assert_eq!(user.get::<i64, _>("is_admin"), 1);
    assert_eq!(
        user.get::<String, _>("created_at"),
        "2026-01-02T03:04:05.123456Z"
    );
    assert_eq!(
        user.get::<Option<String>, _>("profile_image").as_deref(),
        Some("avatar.png")
    );
    assert_eq!(user.get::<i64, _>("legacy_token_version"), 4);

    let token =
        sqlx::query("SELECT id,name,token_hash,created_at,last_used_at,expires_at,user_id FROM api_tokens WHERE id=11")
            .fetch_one(&target)
            .await
            .expect("PAT row is retained");
    assert_eq!(
        token.get::<String, _>("token_hash"),
        "fixture-sha256-token-hash"
    );
    assert_eq!(token.get::<i64, _>("user_id"), 7);
    assert_eq!(
        token.get::<i64, _>("created_at"),
        epoch("2026-01-03T04:05:06.987654Z")
    );
    assert_eq!(
        token.get::<i64, _>("last_used_at"),
        epoch("2026-01-04T05:06:07.123456Z")
    );
    assert_eq!(
        token.get::<i64, _>("expires_at"),
        epoch("2027-01-03T04:05:06.000000Z")
    );

    let category_id: i64 =
        sqlx::query_scalar("SELECT id FROM categories WHERE user_id=7 AND name='Handmade'")
            .fetch_one(&target)
            .await
            .expect("source category ID is retained");
    assert_eq!(category_id, 13);
    assert_eq!(
        sqlx::query_scalar::<_, i64>(
            "SELECT COUNT(*) FROM categories WHERE user_id=7 AND name='Vintage'"
        )
        .fetch_one(&target)
        .await
        .unwrap(),
        1,
    );

    let project = sqlx::query(
        "SELECT category_id,legacy_yarn_text,tags,archive_requested_at,archive_failed,created_at,updated_at FROM projects WHERE id=19",
    )
    .fetch_one(&target)
    .await
    .expect("project ID and legacy metadata are retained");
    assert_eq!(project.get::<i64, _>("category_id"), 13);
    assert_eq!(
        project.get::<String, _>("legacy_yarn_text"),
        "Legacy wool text"
    );
    assert_eq!(project.get::<String, _>("tags"), "[\"sock\",\"wool\"]");
    assert_eq!(project.get::<i64, _>("archive_failed"), 1);
    assert_eq!(
        project.get::<String, _>("created_at"),
        "2026-01-10T11:12:13.000006Z"
    );
    assert_eq!(
        project.get::<String, _>("updated_at"),
        "2026-01-11T12:13:14.000007Z"
    );
    assert_eq!(
        sqlx::query_scalar::<_, i64>(
            "SELECT COUNT(*) FROM project_yarns WHERE project_id=19 AND yarn_id=17"
        )
        .fetch_one(&target)
        .await
        .unwrap(),
        1,
    );
    assert_eq!(
        sqlx::query_scalar::<_, i64>(
            "SELECT COUNT(*) FROM project_steps WHERE id=23 AND project_id=19"
        )
        .fetch_one(&target)
        .await
        .unwrap(),
        1,
    );
    let step_created_at: String =
        sqlx::query_scalar("SELECT created_at FROM project_steps WHERE id=23")
            .fetch_one(&target)
            .await
            .unwrap();
    assert_eq!(step_created_at, "2026-01-12T13:14:15.000008Z");
    let yarn = sqlx::query(
        "SELECT archive_requested_at,archive_failed,is_ai_enhanced,created_at,updated_at FROM yarns WHERE id=17",
    )
    .fetch_one(&target)
    .await
    .unwrap();
    assert_eq!(
        yarn.get::<String, _>("archive_requested_at"),
        "2026-01-06T07:08:09.000002Z"
    );
    assert_eq!(yarn.get::<i64, _>("archive_failed"), 0);
    assert_eq!(yarn.get::<i64, _>("is_ai_enhanced"), 1);

    let audit = sqlx::query(
        "SELECT id,actor_user_id,legacy_actor_user_id,entity_type,entity_id,action,details,created_at FROM audit_events WHERE id=33",
    )
    .fetch_one(&target)
    .await
    .expect("unknown legacy actor event is retained");
    assert_eq!(audit.get::<Option<i64>, _>("actor_user_id"), None);
    assert_eq!(audit.get::<i64, _>("legacy_actor_user_id"), 404);
    assert_eq!(audit.get::<String, _>("action"), "deleted");
    assert_eq!(
        sqlx::query_scalar::<_, i64>(
            "SELECT entity_id FROM deletion_events WHERE entity_type='project'"
        )
        .fetch_one(&target)
        .await
        .unwrap(),
        777,
    );

    let media = sqlx::query(
        "SELECT id,legacy_source,legacy_id,public_id,kind,project_id,yarn_id,is_primary,is_stitch_sample,legacy_is_title_image,legacy_image_type,content_type,is_safe_inline,filename FROM media_files ORDER BY legacy_source,id",
    )
    .fetch_all(&target)
    .await
    .expect("all source media namespaces are imported");
    assert_eq!(media.len(), 6);
    let media_keys: Vec<(String, i64, i64)> = media
        .iter()
        .map(|row| {
            (
                row.get("legacy_source"),
                row.get("legacy_id"),
                row.get("public_id"),
            )
        })
        .collect();
    assert!(media_keys.contains(&("images".to_owned(), 1, 1)));
    assert!(media_keys.contains(&("attachments".to_owned(), 1, 1)));
    assert!(media_keys.contains(&("yarn_images".to_owned(), 1, 1)));
    for row in &media {
        let legacy_source: String = row.get("legacy_source");
        let legacy_id: i64 = row.get("legacy_id");
        let source_relative = match (legacy_source.as_str(), legacy_id) {
            ("images", 1) | ("attachments", 1) => "projects/19/shared.png",
            ("images", 2) => "projects/19/title-a.png",
            ("images", 3) => "projects/19/title-b.png",
            ("attachments", 2) => "projects/19/document.pdf",
            ("yarn_images", 1) => "yarns/17/photo.png",
            _ => panic!("fixture includes only explicitly mapped media IDs"),
        };
        let (subdir, parent_id) = match row.get::<String, _>("kind").as_str() {
            "photo" => ("yarns", row.get::<i64, _>("yarn_id")),
            _ => ("projects", row.get::<i64, _>("project_id")),
        };
        let target_path = fixture
            .destination
            .join("media")
            .join(subdir)
            .join(parent_id.to_string())
            .join(row.get::<String, _>("filename"));
        assert_eq!(
            hash_file(&fixture.source_media.join(source_relative)),
            hash_file(&target_path)
        );
    }
    let image = media
        .iter()
        .find(|row| row.get::<String, _>("legacy_source") == "images")
        .unwrap();
    assert_eq!(image.get::<String, _>("kind"), "step");
    assert_eq!(image.get::<i64, _>("is_primary"), 0);
    assert_eq!(image.get::<Option<i64>, _>("is_stitch_sample"), None);
    assert_eq!(
        image.get::<Option<i64>, _>("legacy_is_title_image"),
        Some(0)
    );
    assert_eq!(image.get::<String, _>("legacy_image_type"), "diagram");
    assert_eq!(image.get::<String, _>("content_type"), "image/png");
    let image_filename: String = image.get("filename");
    assert!(!image_filename.contains("shared"));
    assert_eq!(
        fs::read(
            fixture
                .destination
                .join("media/projects/19")
                .join(&image_filename)
        )
        .unwrap(),
        valid_png()
    );
    let thumbnail = format!(
        "thumb_{}.jpg",
        Path::new(&image_filename)
            .file_stem()
            .unwrap()
            .to_string_lossy()
    );
    assert!(
        fs::read(
            fixture
                .destination
                .join("media/thumbnails/projects/19")
                .join(thumbnail)
        )
        .unwrap()
        .starts_with(&[0xff, 0xd8])
    );
    let title_images = media
        .iter()
        .filter(|row| row.get::<String, _>("legacy_source") == "images")
        .filter(|row| row.get::<i64, _>("legacy_is_title_image") == 1)
        .collect::<Vec<_>>();
    assert_eq!(title_images.len(), 2);
    assert!(
        title_images
            .iter()
            .all(|row| row.get::<i64, _>("is_primary") == 0)
    );
    assert_eq!(
        sqlx::query_scalar::<_, i64>("SELECT COUNT(*) FROM media_url_aliases WHERE subdir='projects' AND parent_id=19 AND legacy_filename='shared.png'")
            .fetch_one(&target)
            .await
            .unwrap(),
        1,
    );
    assert_eq!(
        sqlx::query_scalar::<_, i64>(
            "SELECT COUNT(*) FROM media_files WHERE kind='attachment' AND is_safe_inline=1"
        )
        .fetch_one(&target)
        .await
        .unwrap(),
        2,
    );
    assert_eq!(
        sqlx::query_scalar::<_, i64>(
            "SELECT COUNT(*) FROM user_avatars WHERE user_id=7 AND filename='avatar.png'"
        )
        .fetch_one(&target)
        .await
        .unwrap(),
        1,
    );
    assert_eq!(
        hash_file(&fixture.source_media.join("users/7/avatar.png")),
        hash_file(&fixture.destination.join("media/users/7/avatar.png")),
    );

    let report_contents = fs::read_to_string(fixture.destination.join("migration-report.json"))
        .expect("aggregate report exists");
    assert!(!report_contents.contains("fixture@example.invalid"));
    assert!(!report_contents.contains("fixture-sha256-token-hash"));
    target.close().await;
    assert_eq!(hash_file(&fixture.source_db), db_before);
    assert_eq!(hash_tree(&fixture.source_media), media_before);
    drop(fixture.directory);
}

#[tokio::test]
async fn treats_destination_path_characters_as_filenames_not_sqlite_url_options() {
    let fixture = Fixture::new().await;
    let parent = fixture.directory.path().join("snapshot?mode=ro#fragment");
    fs::create_dir(&parent).unwrap();
    let mut options = fixture.options();
    options.destination = parent.join("rust-data");
    import_snapshot(options.clone()).await.unwrap();
    let pool = SqlitePoolOptions::new()
        .max_connections(1)
        .connect_with(
            SqliteConnectOptions::new()
                .filename(options.destination.join("database.sqlite3"))
                .read_only(true),
        )
        .await
        .unwrap();
    assert_eq!(
        sqlx::query_scalar::<_, i64>("SELECT count(*) FROM users")
            .fetch_one(&pool)
            .await
            .unwrap(),
        1
    );
    pool.close().await;
}

#[tokio::test]
async fn refuses_to_replace_an_existing_destination() {
    let fixture = Fixture::new().await;
    fs::create_dir(&fixture.destination).unwrap();
    let marker = fixture.destination.join("keep.txt");
    fs::write(&marker, b"leave existing data alone").unwrap();

    let error = import_snapshot(fixture.options()).await.unwrap_err();

    assert_eq!(error, ImportError::DestinationExists);
    assert_eq!(fs::read(marker).unwrap(), b"leave existing data alone");
}

#[tokio::test]
async fn preserves_rows_when_distinct_filenames_share_a_legacy_thumbnail_url() {
    let fixture = Fixture::new().await;
    let pool = source_pool(&fixture.source_db).await;
    sqlx::query(
        "INSERT INTO images VALUES (4,'same-stem.png','same-a.png','title','A',1,0,1,1,'2026-01-23 00:00:00',19,NULL), (5,'same-stem.webp','same-b.webp','title','B',1,0,1,1,'2026-01-23 00:00:01',19,NULL)",
    )
    .execute(&pool)
    .await
    .unwrap();
    pool.close().await;
    write_source_file(
        &fixture.source_media,
        "projects",
        19,
        "same-stem.png",
        &valid_png(),
    );
    write_source_file(
        &fixture.source_media,
        "projects",
        19,
        "same-stem.webp",
        &valid_png(),
    );

    let report = import_snapshot(fixture.options()).await.unwrap();

    assert_eq!(report.project_images, 5);
    assert_eq!(report.reused_thumbnail_aliases, 1);
    let target = target_pool(&fixture.destination).await;
    assert_eq!(
        sqlx::query_scalar::<_, i64>(
            "SELECT COUNT(*) FROM media_files WHERE legacy_source='images' AND legacy_id IN (4,5)",
        )
        .fetch_one(&target)
        .await
        .unwrap(),
        2,
    );
    assert_eq!(
        sqlx::query_scalar::<_, i64>(
            "SELECT COUNT(*) FROM media_url_aliases WHERE subdir='projects' AND parent_id=19 AND legacy_filename IN ('same-stem.png','same-stem.webp')",
        )
        .fetch_one(&target)
        .await
        .unwrap(),
        2,
    );
    assert_eq!(
        sqlx::query_scalar::<_, i64>(
            "SELECT COUNT(*) FROM media_url_aliases WHERE subdir='projects' AND parent_id=19 AND legacy_thumbnail='thumb_same-stem.jpg'",
        )
        .fetch_one(&target)
        .await
        .unwrap(),
        1,
    );
    target.close().await;
}

#[tokio::test]
async fn refuses_a_destination_inside_the_source_media_tree() {
    let fixture = Fixture::new().await;
    let source_hash = hash_tree(&fixture.source_media);
    let mut options = fixture.options();
    options.destination = fixture.source_media.join("import-output");

    let error = import_snapshot(options).await.unwrap_err();

    assert_eq!(error, ImportError::InvalidPath);
    assert_eq!(hash_tree(&fixture.source_media), source_hash);
    assert!(!fixture.source_media.join("import-output").exists());
}

#[tokio::test]
async fn normalizes_imported_email_like_auth_and_preserves_the_source_value() {
    let fixture = Fixture::new().await;
    let source = source_pool(&fixture.source_db).await;
    sqlx::query("UPDATE users SET email=' ÄLICE@Example.invalid ' WHERE id=7")
        .execute(&source)
        .await
        .unwrap();
    source.close().await;

    import_snapshot(fixture.options()).await.unwrap();

    let target = target_pool(&fixture.destination).await;
    let (email, legacy_email): (String, String) =
        sqlx::query_as("SELECT email,legacy_email FROM users WHERE id=7")
            .fetch_one(&target)
            .await
            .unwrap();
    assert_eq!(email, "älice@example.invalid");
    assert_eq!(legacy_email, " ÄLICE@Example.invalid ");
    target.close().await;
}

#[tokio::test]
async fn missing_media_rolls_back_and_never_publishes_a_partial_directory() {
    let fixture = Fixture::new().await;
    fs::remove_file(fixture.source_media.join("projects/19/shared.png")).unwrap();

    let error = import_snapshot(fixture.options()).await.unwrap_err();

    assert!(matches!(error, ImportError::InvalidMedia { count: 2 }));
    assert!(!fixture.destination.exists());
}

#[tokio::test]
async fn case_colliding_source_categories_fail_without_merging() {
    let fixture = Fixture::new().await;
    let pool = source_pool(&fixture.source_db).await;
    sqlx::query("INSERT INTO categories VALUES (14,'handmade','2026-01-21 22:23:24.000018',7)")
        .execute(&pool)
        .await
        .unwrap();
    pool.close().await;

    let error = import_snapshot(fixture.options()).await.unwrap_err();

    assert!(matches!(
        error,
        ImportError::IdentityCollision {
            emails: 0,
            categories: 1
        }
    ));
    assert!(!fixture.destination.exists());
}

#[tokio::test]
async fn case_colliding_source_emails_fail_without_merging() {
    let fixture = Fixture::new().await;
    let pool = source_pool(&fixture.source_db).await;
    sqlx::query(
        "INSERT INTO users VALUES (8,'FIXTURE@example.invalid','fixture-hash',1,0,0,'2026-01-22 23:24:25.000019',NULL)",
    )
    .execute(&pool)
    .await
    .unwrap();
    pool.close().await;

    let error = import_snapshot(fixture.options()).await.unwrap_err();

    assert!(matches!(
        error,
        ImportError::IdentityCollision {
            emails: 1,
            categories: 0
        }
    ));
    assert!(!fixture.destination.exists());
}

#[tokio::test]
async fn rejects_wal_sidecars_and_noncanonical_paths_before_opening_source() {
    let fixture = Fixture::new().await;
    let wal = PathBuf::from(format!("{}-wal", fixture.source_db.display()));
    fs::write(&wal, b"pending WAL snapshot").unwrap();

    let error = import_snapshot(fixture.options()).await.unwrap_err();

    assert_eq!(error, ImportError::SourceOpen);
    assert!(!fixture.destination.exists());
    fs::remove_file(wal).unwrap();
    drop(fixture.directory);
}

#[tokio::test]
async fn rejects_noncanonical_and_escaping_paths() {
    let fixture = Fixture::new().await;
    let mut options = fixture.options();
    options.source_db = fixture.source_db.parent().unwrap().join("..");

    let error = import_snapshot(options).await.unwrap_err();

    assert_eq!(error, ImportError::InvalidPath);
    assert!(!fixture.destination.exists());
}

async fn source_pool(path: &Path) -> SqlitePool {
    SqlitePoolOptions::new()
        .max_connections(1)
        .connect_with(
            SqliteConnectOptions::new()
                .filename(path)
                .create_if_missing(true)
                .foreign_keys(false),
        )
        .await
        .expect("source fixture database opens")
}

async fn target_pool(destination: &Path) -> SqlitePool {
    let database = destination.join("database.sqlite3");
    let url = format!("sqlite://{}", database.display());
    stricknani_server::db::connect(&url)
        .await
        .expect("published Rust database opens")
}

fn write_source_file(root: &Path, subdir: &str, parent_id: i64, filename: &str, bytes: &[u8]) {
    let directory = root.join(subdir).join(parent_id.to_string());
    fs::create_dir_all(&directory).unwrap();
    fs::write(directory.join(filename), bytes).unwrap();
}

fn valid_png() -> Vec<u8> {
    let image = DynamicImage::ImageRgba8(RgbaImage::from_pixel(1, 1, Rgba([255, 0, 0, 255])));
    let mut bytes = std::io::Cursor::new(Vec::new());
    image
        .write_to(&mut bytes, ImageFormat::Png)
        .expect("fixture image encodes as PNG");
    bytes.into_inner()
}

fn epoch(timestamp: &str) -> i64 {
    chrono::DateTime::parse_from_rfc3339(timestamp)
        .unwrap()
        .timestamp()
}

fn hash_file(path: &Path) -> [u8; 32] {
    Sha256::digest(fs::read(path).unwrap()).into()
}

fn hash_tree(root: &Path) -> Vec<(PathBuf, [u8; 32])> {
    let mut entries = Vec::new();
    let mut pending = vec![root.to_path_buf()];
    while let Some(path) = pending.pop() {
        for entry in fs::read_dir(path).unwrap() {
            let entry = entry.unwrap();
            let path = entry.path();
            if path.is_dir() {
                pending.push(path);
            } else {
                entries.push((
                    path.strip_prefix(root).unwrap().to_path_buf(),
                    hash_file(&path),
                ));
            }
        }
    }
    entries.sort_by(|left, right| left.0.cmp(&right.0));
    entries
}
