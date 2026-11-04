use sqlx::{Row, sqlite::SqlitePoolOptions};

#[tokio::test]
async fn media_order_migration_preserves_display_ranks_and_effective_project_cover() {
    let pool = SqlitePoolOptions::new()
        .max_connections(1)
        .connect("sqlite::memory:")
        .await
        .unwrap();
    sqlx::raw_sql(
        "CREATE TABLE media_files (
            id INTEGER PRIMARY KEY,
            project_id INTEGER,
            yarn_id INTEGER,
            step_id INTEGER,
            kind TEXT NOT NULL,
            is_primary INTEGER NOT NULL DEFAULT 0,
            legacy_is_title_image INTEGER
        );
        CREATE UNIQUE INDEX media_files_primary_title
            ON media_files(project_id) WHERE kind = 'title' AND is_primary = 1;
        INSERT INTO media_files VALUES
            (10,1,NULL,91,'step',0,0),
            (100,1,NULL,NULL,'title',1,NULL),
            (200,2,NULL,92,'step',0,1),
            (300,2,NULL,NULL,'title',1,NULL),
            (401,3,NULL,NULL,'title',0,0),
            (402,3,NULL,NULL,'title',0,NULL),
            (502,4,NULL,NULL,'title',0,1),
            (503,4,NULL,NULL,'title',0,1),
            (510,NULL,5,NULL,'photo',1,NULL),
            (511,NULL,5,NULL,'photo',0,NULL),
            (512,NULL,5,NULL,'photo',0,NULL),
            (600,NULL,6,NULL,'photo',0,NULL),
            (601,NULL,6,NULL,'photo',0,NULL);
        ",
    )
    .execute(&pool)
    .await
    .unwrap();

    let legacy_v1_title_flags = sqlx::query_as::<_, (i64, bool)>(
        "SELECT id,COALESCE(legacy_is_title_image, kind = 'title' AND is_primary) \
         FROM media_files WHERE kind IN ('title','step') ORDER BY id",
    )
    .fetch_all(&pool)
    .await
    .unwrap();

    sqlx::raw_sql(include_str!("../migrations/0015_media_position.sql"))
        .execute(&pool)
        .await
        .unwrap();

    let positions = sqlx::query("SELECT id,position FROM media_files ORDER BY id")
        .fetch_all(&pool)
        .await
        .unwrap()
        .into_iter()
        .map(|row| (row.get::<i64, _>("id"), row.get::<i64, _>("position")))
        .collect::<std::collections::HashMap<_, _>>();
    assert_eq!(positions[&100], 0);
    assert_eq!(positions[&10], 0);
    assert_eq!(positions[&200], 0);
    assert_eq!(positions[&300], 0);
    assert_eq!(positions[&401], 0);
    assert_eq!(positions[&402], 1);
    assert_eq!(positions[&502], 0);
    assert_eq!(positions[&503], 1);
    assert_eq!(positions[&510], 0);
    assert_eq!(positions[&512], 1);
    assert_eq!(positions[&511], 2);
    assert_eq!(positions[&601], 0);
    assert_eq!(positions[&600], 1);

    let project_covers = sqlx::query_scalar::<_, i64>(
        "SELECT id FROM media_files WHERE project_id = ? AND is_project_cover = 1",
    )
    .bind(1)
    .fetch_one(&pool)
    .await
    .unwrap();
    assert_eq!(
        project_covers, 100,
        "native title fallback must win over an unflagged step image"
    );
    let legacy_step_cover = sqlx::query_scalar::<_, i64>(
        "SELECT id FROM media_files WHERE project_id = ? AND is_project_cover = 1",
    )
    .bind(2)
    .fetch_one(&pool)
    .await
    .unwrap();
    assert_eq!(
        legacy_step_cover, 200,
        "legacy flagged step cover remains selected"
    );
    let fallback_cover = sqlx::query_scalar::<_, i64>(
        "SELECT id FROM media_files WHERE project_id = ? AND is_project_cover = 1",
    )
    .bind(3)
    .fetch_one(&pool)
    .await
    .unwrap();
    assert_eq!(fallback_cover, 401);
    let flagged_cover = sqlx::query_scalar::<_, i64>(
        "SELECT id FROM media_files WHERE project_id = ? AND is_project_cover = 1",
    )
    .bind(4)
    .fetch_one(&pool)
    .await
    .unwrap();
    assert_eq!(flagged_cover, 502);
    let legacy_flags = sqlx::query_scalar::<_, Option<bool>>(
        "SELECT legacy_is_title_image FROM media_files WHERE id = 200",
    )
    .fetch_one(&pool)
    .await
    .unwrap();
    assert_eq!(legacy_flags, Some(true));
    let v1_flags_after = sqlx::query_as::<_, (i64, bool)>(
        "SELECT id,COALESCE(legacy_is_title_image, kind = 'title' AND is_primary) \
         FROM media_files WHERE kind IN ('title','step') ORDER BY id",
    )
    .fetch_all(&pool)
    .await
    .unwrap();
    assert_eq!(v1_flags_after, legacy_v1_title_flags);
    assert!(
        v1_flags_after.contains(&(300, true)),
        "native title v1 primary survives a flagged legacy step cover"
    );

    let duplicate_title =
        sqlx::query("INSERT INTO media_files(id,project_id,kind,position) VALUES(900,1,'title',0)")
            .execute(&pool)
            .await;
    assert!(
        duplicate_title.is_err(),
        "title positions are unique even with NULL step_id"
    );
    let duplicate_step = sqlx::query(
        "INSERT INTO media_files(id,project_id,step_id,kind,position) VALUES(901,1,91,'step',0)",
    )
    .execute(&pool)
    .await;
    assert!(
        duplicate_step.is_err(),
        "positions are unique within a step"
    );
    sqlx::query(
        "INSERT INTO media_files(id,project_id,step_id,kind,position) VALUES(902,1,999,'step',0)",
    )
    .execute(&pool)
    .await
    .unwrap();
    let unmarked_photo_primaries: i64 =
        sqlx::query_scalar("SELECT COUNT(*) FROM media_files WHERE yarn_id = 6 AND is_primary = 1")
            .fetch_one(&pool)
            .await
            .unwrap();
    assert_eq!(
        unmarked_photo_primaries, 0,
        "migration must preserve an all-false legacy primary group"
    );
    pool.close().await;
}
