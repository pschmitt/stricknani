use std::time::Duration;
use stricknani_server::db;

async fn file_pool(dir: &tempfile::TempDir) -> sqlx::SqlitePool {
    let url = format!("sqlite:{}", dir.path().join("test.sqlite3").display());
    db::connect(&url).await.expect("file database should open")
}

#[tokio::test]
async fn file_database_uses_wal_and_several_connections() {
    let dir = tempfile::tempdir().unwrap();
    let pool = file_pool(&dir).await;
    let mode: String = sqlx::query_scalar("PRAGMA journal_mode")
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(mode, "wal");
    // Hold two connections at once: only possible with a multi-connection pool.
    let first = pool.acquire().await.unwrap();
    let second = tokio::time::timeout(Duration::from_secs(2), pool.acquire())
        .await
        .expect("a second connection should be available")
        .unwrap();
    drop((first, second));
    let foreign_keys: i64 = sqlx::query_scalar("PRAGMA foreign_keys")
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(foreign_keys, 1);
}

#[tokio::test]
async fn concurrent_write_transactions_do_not_hit_busy() {
    let dir = tempfile::tempdir().unwrap();
    let pool = file_pool(&dir).await;
    sqlx::query("CREATE TABLE scratch_counter (id INTEGER PRIMARY KEY, n INTEGER NOT NULL)")
        .execute(&pool)
        .await
        .unwrap();
    sqlx::query("INSERT INTO scratch_counter (id, n) VALUES (1, 0)")
        .execute(&pool)
        .await
        .unwrap();
    let mut tasks = Vec::new();
    for _ in 0..20 {
        let pool = pool.clone();
        tasks.push(tokio::spawn(async move {
            let mut tx = db::begin_write(&pool).await?;
            // Read first, then write: a deferred transaction fails here.
            let n: i64 = sqlx::query_scalar("SELECT n FROM scratch_counter WHERE id = 1")
                .fetch_one(&mut *tx)
                .await?;
            tokio::time::sleep(Duration::from_millis(5)).await;
            sqlx::query("UPDATE scratch_counter SET n = ? WHERE id = 1")
                .bind(n + 1)
                .execute(&mut *tx)
                .await?;
            tx.commit().await
        }));
    }
    for task in tasks {
        task.await
            .unwrap()
            .expect("write transaction should succeed");
    }
    let n: i64 = sqlx::query_scalar("SELECT n FROM scratch_counter WHERE id = 1")
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(n, 20);
}

#[tokio::test]
async fn reads_do_not_wait_for_an_open_write_transaction() {
    let dir = tempfile::tempdir().unwrap();
    let pool = file_pool(&dir).await;
    sqlx::query("CREATE TABLE scratch_rows (id INTEGER PRIMARY KEY, v TEXT NOT NULL)")
        .execute(&pool)
        .await
        .unwrap();
    sqlx::query("INSERT INTO scratch_rows (v) VALUES ('committed')")
        .execute(&pool)
        .await
        .unwrap();
    let mut tx = db::begin_write(&pool).await.unwrap();
    sqlx::query("INSERT INTO scratch_rows (v) VALUES ('pending')")
        .execute(&mut *tx)
        .await
        .unwrap();
    let count = tokio::time::timeout(
        Duration::from_secs(2),
        sqlx::query_scalar::<_, i64>("SELECT count(*) FROM scratch_rows").fetch_one(&pool),
    )
    .await
    .expect("a read should not block behind the open write transaction")
    .unwrap();
    assert_eq!(count, 1, "readers see the last committed state");
    tx.commit().await.unwrap();
    let count: i64 = sqlx::query_scalar("SELECT count(*) FROM scratch_rows")
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(count, 2);
}

#[tokio::test]
async fn in_memory_database_keeps_one_connection_and_no_wal() {
    let pool = db::connect("sqlite::memory:").await.unwrap();
    let mode: String = sqlx::query_scalar("PRAGMA journal_mode")
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(mode, "memory");
    let held = pool.acquire().await.unwrap();
    assert!(
        tokio::time::timeout(Duration::from_millis(200), pool.acquire())
            .await
            .is_err(),
        "an in-memory pool must not open a second (empty) database"
    );
    drop(held);
    let mut tx = db::begin_write(&pool).await.unwrap();
    sqlx::query("SELECT 1").execute(&mut *tx).await.unwrap();
    tx.commit().await.unwrap();
}
