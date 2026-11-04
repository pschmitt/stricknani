use stricknani_server::db::connect;

#[tokio::test]
async fn migration_preserves_legacy_metadata_and_relationship_keys() {
    let pool = connect("sqlite::memory:").await.unwrap();
    let user_id = sqlx::query(
        "INSERT INTO users (email, password_hash, profile_image, legacy_token_version) \
         VALUES ('legacy@example.invalid', 'test-hash', 'avatar.png', 7)",
    )
    .execute(&pool)
    .await
    .unwrap()
    .last_insert_rowid();
    let category_id = sqlx::query(
        "INSERT INTO categories (user_id, name, created_at) \
         VALUES (?, 'Knits', '2020-01-02T03:04:05Z')",
    )
    .bind(user_id)
    .execute(&pool)
    .await
    .unwrap()
    .last_insert_rowid();
    let category_created_at: String =
        sqlx::query_scalar("SELECT created_at FROM categories WHERE id = ?")
            .bind(category_id)
            .fetch_one(&pool)
            .await
            .unwrap();
    assert_eq!(category_created_at, "2020-01-02T03:04:05Z");
    let project_id = sqlx::query(
        "INSERT INTO projects \
            (owner_id, category_id, name, legacy_yarn_text, archive_requested_at, archive_failed, created_at, updated_at) \
         VALUES (?, ?, 'Cardigan', 'old yarn field', '2021-02-03T04:05:06Z', 1, '2020-01-02T03:04:05Z', '2020-01-02T03:04:05Z')",
    )
    .bind(user_id)
    .bind(category_id)
    .execute(&pool)
    .await
    .unwrap()
    .last_insert_rowid();
    let step_id = sqlx::query(
        "INSERT INTO project_steps (project_id, title, step_number, created_at) \
         VALUES (?, 'Sleeves', 1, '2020-01-03T03:04:05Z')",
    )
    .bind(project_id)
    .execute(&pool)
    .await
    .unwrap()
    .last_insert_rowid();
    let step_created_at: String =
        sqlx::query_scalar("SELECT created_at FROM project_steps WHERE id = ?")
            .bind(step_id)
            .fetch_one(&pool)
            .await
            .unwrap();
    assert_eq!(step_created_at, "2020-01-03T03:04:05Z");
    let media_id = sqlx::query(
        "INSERT INTO media_files \
            (owner_id, project_id, step_id, kind, public_id, filename, original_filename, content_type, size_bytes, \
             created_at, legacy_image_type, is_stitch_sample, legacy_source, legacy_id) \
         VALUES (?, ?, ?, 'step', 3, 'stored.webp', 'source.jpg', 'image/webp', 20, \
                 '2020-01-04T03:04:05Z', 'image/jpeg', 1, 'images', 3)",
    )
    .bind(user_id)
    .bind(project_id)
    .bind(step_id)
    .execute(&pool)
    .await
    .unwrap()
    .last_insert_rowid();
    sqlx::query(
        "INSERT INTO user_avatars \
            (user_id, filename, original_filename, content_type, width, height, created_at) \
         VALUES (?, 'avatar.png', 'portrait.png', 'image/png', 64, 64, '2020-01-05T03:04:05Z')",
    )
    .bind(user_id)
    .execute(&pool)
    .await
    .unwrap();
    assert!(
        sqlx::query("INSERT INTO user_avatars (user_id, filename) VALUES (-1, 'orphan.png')",)
            .execute(&pool)
            .await
            .is_err(),
        "avatars must reference an account"
    );
    let audit_id = sqlx::query(
        "INSERT INTO audit_events \
            (actor_user_id, legacy_actor_user_id, entity_type, entity_id, action, details, created_at) \
         VALUES (?, ?, 'project', ?, 'updated', '{\"field\":\"name\"}', '2020-01-06T03:04:05Z')",
    )
    .bind(user_id)
    .bind(user_id)
    .bind(project_id)
    .execute(&pool)
    .await
    .unwrap()
    .last_insert_rowid();
    sqlx::query(
        "INSERT INTO api_tokens (user_id, name, token_hash, created_at, last_used_at) \
         VALUES (?, 'phone', 'test-only-sha256', 10, 20)",
    )
    .bind(user_id)
    .execute(&pool)
    .await
    .unwrap();

    let account: (String, i64) =
        sqlx::query_as("SELECT profile_image, legacy_token_version FROM users WHERE id = ?")
            .bind(user_id)
            .fetch_one(&pool)
            .await
            .unwrap();
    assert_eq!(account, ("avatar.png".into(), 7));

    let token_last_used: i64 =
        sqlx::query_scalar("SELECT last_used_at FROM api_tokens WHERE user_id = ?")
            .bind(user_id)
            .fetch_one(&pool)
            .await
            .unwrap();
    assert_eq!(token_last_used, 20);

    let project_metadata: (String, String, i64) = sqlx::query_as(
        "SELECT legacy_yarn_text, archive_requested_at, archive_failed \
         FROM projects WHERE id = ?",
    )
    .bind(project_id)
    .fetch_one(&pool)
    .await
    .unwrap();
    assert_eq!(
        project_metadata,
        ("old yarn field".into(), "2021-02-03T04:05:06Z".into(), 1)
    );

    let media_metadata: (i64, String, String, i64, String, i64) = sqlx::query_as(
        "SELECT id, created_at, legacy_image_type, is_stitch_sample, legacy_source, legacy_id \
         FROM media_files WHERE id = ?",
    )
    .bind(media_id)
    .fetch_one(&pool)
    .await
    .unwrap();
    assert_eq!(
        media_metadata,
        (
            media_id,
            "2020-01-04T03:04:05Z".into(),
            "image/jpeg".into(),
            1,
            "images".into(),
            3
        )
    );

    sqlx::query("DELETE FROM users WHERE id = ?")
        .bind(user_id)
        .execute(&pool)
        .await
        .unwrap();
    let actors: (Option<i64>, Option<i64>) =
        sqlx::query_as("SELECT actor_user_id, legacy_actor_user_id FROM audit_events WHERE id = ?")
            .bind(audit_id)
            .fetch_one(&pool)
            .await
            .unwrap();
    assert_eq!(actors, (None, Some(user_id)));
    sqlx::query(
        "INSERT INTO audit_events \
            (actor_user_id, legacy_actor_user_id, entity_type, entity_id, action, created_at) \
         VALUES (NULL, 9876, 'project', ?, 'updated', '2020-01-07T03:04:05Z')",
    )
    .bind(project_id)
    .execute(&pool)
    .await
    .unwrap();
    let dangling_actor: (Option<i64>, Option<i64>) = sqlx::query_as(
        "SELECT actor_user_id, legacy_actor_user_id FROM audit_events \
         WHERE legacy_actor_user_id = 9876",
    )
    .fetch_one(&pool)
    .await
    .unwrap();
    assert_eq!(dangling_actor, (None, Some(9876)));
}

#[tokio::test]
async fn yarn_archive_metadata_defaults_and_source_values_are_preserved() {
    let pool = connect("sqlite::memory:").await.unwrap();
    let user_id = sqlx::query(
        "INSERT INTO users (email, password_hash) VALUES ('legacy@example.invalid', 'test-hash')",
    )
    .execute(&pool)
    .await
    .unwrap()
    .last_insert_rowid();
    sqlx::query(
        "INSERT INTO yarns (owner_id, name, created_at, updated_at) \
         VALUES (?, 'Plain yarn', '2020-01-01T00:00:00Z', '2020-01-01T00:00:00Z')",
    )
    .bind(user_id)
    .execute(&pool)
    .await
    .unwrap();
    let default_failed: i64 = sqlx::query_scalar("SELECT archive_failed FROM yarns")
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(default_failed, 0);

    sqlx::query("UPDATE yarns SET archive_requested_at = ?, archive_failed = 1 WHERE owner_id = ?")
        .bind("2022-02-03T04:05:06Z")
        .bind(user_id)
        .execute(&pool)
        .await
        .unwrap();
    let metadata: (String, i64) =
        sqlx::query_as("SELECT archive_requested_at, archive_failed FROM yarns")
            .fetch_one(&pool)
            .await
            .unwrap();
    assert_eq!(metadata, ("2022-02-03T04:05:06Z".into(), 1));
}

#[tokio::test]
async fn nullable_legacy_flags_and_source_scoped_media_ids_remain_distinct() {
    let pool = connect("sqlite::memory:").await.unwrap();
    let user_id = sqlx::query(
        "INSERT INTO users (email, password_hash) VALUES ('legacy@example.invalid', 'test-hash')",
    )
    .execute(&pool)
    .await
    .unwrap()
    .last_insert_rowid();
    let project_id = sqlx::query(
        "INSERT INTO projects (owner_id, name, created_at, updated_at) \
         VALUES (?, 'Project', '2020-01-01T00:00:00Z', '2020-01-01T00:00:00Z')",
    )
    .bind(user_id)
    .execute(&pool)
    .await
    .unwrap()
    .last_insert_rowid();
    let yarn_id = sqlx::query(
        "INSERT INTO yarns (owner_id, name, created_at, updated_at) \
         VALUES (?, 'Photo yarn', '2020-01-01T00:00:00Z', '2020-01-01T00:00:00Z')",
    )
    .bind(user_id)
    .execute(&pool)
    .await
    .unwrap()
    .last_insert_rowid();
    let defaults: (i64, i64) = sqlx::query_as(
        "SELECT legacy_token_version, archive_failed FROM users CROSS JOIN projects \
         WHERE users.id = ? AND projects.id = ?",
    )
    .bind(user_id)
    .bind(project_id)
    .fetch_one(&pool)
    .await
    .unwrap();
    assert_eq!(defaults, (0, 0));

    for (source, kind, project_id, yarn_id, stitch_sample) in [
        ("images", "title", Some(project_id), None, Some(1_i64)),
        ("yarn_images", "photo", None, Some(yarn_id), None),
    ] {
        sqlx::query(
            "INSERT INTO media_files \
                (owner_id, project_id, yarn_id, kind, public_id, filename, original_filename, \
                 content_type, size_bytes, is_stitch_sample, legacy_source, legacy_id) \
             VALUES (?, ?, ?, ?, 1, ?, 'same-id.jpg', 'image/jpeg', 1, ?, ?, 4)",
        )
        .bind(user_id)
        .bind(project_id)
        .bind(yarn_id)
        .bind(kind)
        .bind(format!("{source}.jpg"))
        .bind(stitch_sample)
        .bind(source)
        .execute(&pool)
        .await
        .unwrap();
    }

    let rows: Vec<(String, i64, Option<i64>)> = sqlx::query_as(
        "SELECT legacy_source, public_id, is_stitch_sample FROM media_files \
         WHERE legacy_id = 4 ORDER BY legacy_source",
    )
    .fetch_all(&pool)
    .await
    .unwrap();
    assert_eq!(
        rows,
        vec![
            ("images".into(), 1, Some(1)),
            ("yarn_images".into(), 1, None)
        ]
    );
}
