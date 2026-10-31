use std::{
    io::Write,
    process::{Command, Stdio},
};
use stricknani_server::db;

fn cli(url: &str, args: &[&str], input: Option<&[u8]>) -> std::process::Output {
    let mut child = Command::new(env!("CARGO_BIN_EXE_stricknani-server"))
        .args(args)
        .env("RUST_DATABASE_URL", url)
        .env(
            "RUST_MEDIA_ROOT",
            std::env::temp_dir().join("stricknani-cli-test-media"),
        )
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .unwrap();
    if let Some(input) = input {
        child.stdin.take().unwrap().write_all(input).unwrap();
    } else {
        drop(child.stdin.take());
    }
    child.wait_with_output().unwrap()
}

#[tokio::test]
async fn account_cli_creates_disables_and_revokes_without_exposing_passwords() {
    let path = std::env::temp_dir().join(format!("stricknani-cli-{}.db", rand::random::<u64>()));
    let url = format!("sqlite:{}", path.display());
    let created = cli(
        &url,
        &["create-user", "cli@example.invalid"],
        Some(b"disposable-test-password\n"),
    );
    assert!(
        created.status.success(),
        "{}",
        String::from_utf8_lossy(&created.stderr)
    );
    assert_eq!(
        String::from_utf8_lossy(&created.stdout).trim(),
        "user created"
    );
    assert!(created.stderr.is_empty());
    let pool = db::connect(&url).await.unwrap();
    let user_id: i64 = sqlx::query_scalar("SELECT id FROM users")
        .fetch_one(&pool)
        .await
        .unwrap();
    sqlx::query("INSERT INTO api_tokens (user_id,name,token_hash,created_at) VALUES (?,'test','test-hash',unixepoch())")
        .bind(user_id).execute(&pool).await.unwrap();
    let token_id: i64 = sqlx::query_scalar("SELECT id FROM api_tokens")
        .fetch_one(&pool)
        .await
        .unwrap();
    assert!(
        cli(&url, &["revoke-token", &token_id.to_string()], None)
            .status
            .success()
    );
    let revoked: i64 = sqlx::query_scalar("SELECT revoked_at IS NOT NULL FROM api_tokens")
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(revoked, 1);
    assert!(
        cli(&url, &["disable-user", "CLI@example.invalid"], None)
            .status
            .success()
    );
    let active: i64 = sqlx::query_scalar("SELECT active FROM users")
        .fetch_one(&pool)
        .await
        .unwrap();
    assert_eq!(active, 0);
    assert!(!cli(&url, &["revoke-token", "-1"], None).status.success());
    pool.close().await;
    std::fs::remove_file(path).unwrap();
}

#[test]
fn help_needs_no_database_and_invalid_config_is_redacted() {
    let help = Command::new(env!("CARGO_BIN_EXE_stricknani-server"))
        .arg("--help")
        .env_remove("RUST_DATABASE_URL")
        .env_remove("RUST_MEDIA_ROOT")
        .output()
        .unwrap();
    assert!(help.status.success());
    let failed = cli("invalid-sensitive-test-value", &["serve"], None);
    assert!(!failed.status.success());
    assert!(!String::from_utf8_lossy(&failed.stderr).contains("invalid-sensitive-test-value"));
}
