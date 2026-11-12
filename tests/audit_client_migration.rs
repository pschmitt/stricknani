use stricknani_server::db::connect;

#[tokio::test]
async fn audit_client_columns_exist_stay_null_for_old_rows_and_are_constrained() {
    let pool = connect("sqlite::memory:").await.unwrap();
    sqlx::query(
        "INSERT INTO audit_events (entity_type, entity_id, action, created_at) \
         VALUES ('project', 1, 'created', '2020-01-01T00:00:00Z')",
    )
    .execute(&pool)
    .await
    .unwrap();
    let row: (
        Option<String>,
        Option<String>,
        Option<String>,
        Option<String>,
    ) = sqlx::query_as(
        "SELECT client_kind, client_version, client_label, client_user_agent FROM audit_events",
    )
    .fetch_one(&pool)
    .await
    .unwrap();
    assert_eq!(row, (None, None, None, None));

    let insert = |kind: &'static str, user_agent: String| {
        let pool = pool.clone();
        async move {
            sqlx::query(
                "INSERT INTO audit_events \
                 (entity_type, entity_id, action, created_at, client_kind, client_user_agent) \
                 VALUES ('project', 2, 'created', '2020-01-01T00:00:00Z', ?, ?)",
            )
            .bind(kind)
            .bind(user_agent)
            .execute(&pool)
            .await
        }
    };
    assert!(insert("android", "okhttp/4.12.0".to_owned()).await.is_ok());
    assert!(insert("system", String::new()).await.is_ok());
    assert!(
        insert("toaster", String::new()).await.is_err(),
        "unknown kind"
    );
    assert!(
        insert("web", "x".repeat(257)).await.is_err(),
        "user agent above 256 characters"
    );
}
