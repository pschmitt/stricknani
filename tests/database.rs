use stricknani_server::db::{connect, create_user};

#[tokio::test]
async fn migrations_are_repeatable_and_enforce_ownership_relations() {
    let pool = connect("sqlite::memory:").await.unwrap();
    sqlx::migrate!().run(&pool).await.unwrap();
    let invalid = sqlx::query("INSERT INTO api_tokens (user_id,name,token_hash,created_at) VALUES (99,'device','not-a-token',0)")
        .execute(&pool).await;
    assert!(invalid.is_err(), "orphan credentials must be rejected");
}

#[tokio::test]
async fn accounts_normalize_email_and_reject_duplicate_identity() {
    let pool = connect("sqlite::memory:").await.unwrap();
    create_user(
        &pool,
        " Example@Example.invalid ",
        "test-only-password".into(),
    )
    .await
    .unwrap();
    assert!(
        create_user(
            &pool,
            "example@example.invalid",
            "test-only-password".into()
        )
        .await
        .is_err()
    );
    let hash: String = sqlx::query_scalar("SELECT password_hash FROM users")
        .fetch_one(&pool)
        .await
        .unwrap();
    assert!(hash.starts_with("$argon2id$"));
    assert!(!hash.contains("test-only-password"));
}

#[tokio::test]
async fn malformed_database_and_weak_account_fail_closed() {
    assert!(connect("postgres://unused").await.is_err());
    let pool = connect("sqlite::memory:").await.unwrap();
    assert!(create_user(&pool, "invalid", "short".into()).await.is_err());
}

#[tokio::test]
async fn refuses_python_database_without_installing_rust_migrations() {
    use sqlx::sqlite::SqlitePoolOptions;
    let path =
        std::env::temp_dir().join(format!("stricknani-foreign-{}.db", rand::random::<u64>()));
    let url = format!("sqlite:{}?mode=rwc", path.display());
    let pool = SqlitePoolOptions::new().connect(&url).await.unwrap();
    sqlx::query("CREATE TABLE alembic_version (version_num TEXT)")
        .execute(&pool)
        .await
        .unwrap();
    assert!(matches!(
        connect(&url).await,
        Err(stricknani_server::db::DatabaseError::ForeignDatabase)
    ));
    let count: i64 =
        sqlx::query_scalar("SELECT count(*) FROM sqlite_master WHERE name = '_sqlx_migrations'")
            .fetch_one(&pool)
            .await
            .unwrap();
    assert_eq!(count, 0, "refusal must precede any migration writes");
    pool.close().await;
    std::fs::remove_file(path).unwrap();
}
