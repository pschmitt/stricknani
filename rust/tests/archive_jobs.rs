use std::{
    collections::VecDeque,
    net::IpAddr,
    sync::{
        Arc, Mutex,
        atomic::{AtomicUsize, Ordering},
    },
};

use sqlx::SqlitePool;
use stricknani_server::{
    archive_jobs::{
        ArchiveEntityKind, ArchiveJobState, ArchiveProcessResult, enqueue, process_one,
    },
    db,
    imports::{DnsResolver, FetchError},
    wayback::{WaybackClient, WaybackError, WaybackFuture, WaybackTransport},
};
use tokio::sync::Semaphore;
use url::Url;

struct FixtureResolver;

impl DnsResolver for FixtureResolver {
    async fn resolve<'a>(&'a self, _hostname: &'a str) -> Result<Vec<IpAddr>, FetchError> {
        Ok(vec!["8.8.8.8".parse().expect("fixture IP should parse")])
    }
}

type TransportResult = Result<Option<Url>, WaybackError>;

struct FixtureTransport {
    saves: Mutex<VecDeque<TransportResult>>,
    availability: Mutex<VecDeque<TransportResult>>,
    calls: Mutex<Vec<&'static str>>,
}

struct ReclaimTransport {
    calls: AtomicUsize,
    first_started: Semaphore,
    release_first: Semaphore,
    first_snapshot: Url,
    second_snapshot: Url,
}

impl WaybackTransport for ReclaimTransport {
    fn save<'a>(
        &'a self,
        _source_url: &'a Url,
    ) -> WaybackFuture<'a, Result<Option<Url>, WaybackError>> {
        let attempt = self.calls.fetch_add(1, Ordering::SeqCst);
        Box::pin(async move {
            if attempt == 0 {
                self.first_started.add_permits(1);
                self.release_first
                    .acquire()
                    .await
                    .expect("fixture release semaphore should remain open")
                    .forget();
                Ok(Some(self.first_snapshot.clone()))
            } else {
                Ok(Some(self.second_snapshot.clone()))
            }
        })
    }

    fn availability<'a>(
        &'a self,
        _source_url: &'a Url,
    ) -> WaybackFuture<'a, Result<Option<Url>, WaybackError>> {
        Box::pin(async { Ok(None) })
    }
}

impl FixtureTransport {
    fn new(saves: Vec<TransportResult>, availability: Vec<TransportResult>) -> Self {
        Self {
            saves: Mutex::new(saves.into()),
            availability: Mutex::new(availability.into()),
            calls: Mutex::new(Vec::new()),
        }
    }

    fn pop_result(results: &Mutex<VecDeque<TransportResult>>) -> TransportResult {
        results
            .lock()
            .expect("fixture response list should not be poisoned")
            .pop_front()
            .unwrap_or(Err(WaybackError::TransportFailed))
    }
}

impl WaybackTransport for FixtureTransport {
    fn save<'a>(
        &'a self,
        _source_url: &'a Url,
    ) -> WaybackFuture<'a, Result<Option<Url>, WaybackError>> {
        self.calls
            .lock()
            .expect("fixture call list should not be poisoned")
            .push("save");
        let result = Self::pop_result(&self.saves);
        Box::pin(async move { result })
    }

    fn availability<'a>(
        &'a self,
        _source_url: &'a Url,
    ) -> WaybackFuture<'a, Result<Option<Url>, WaybackError>> {
        self.calls
            .lock()
            .expect("fixture call list should not be poisoned")
            .push("availability");
        let result = Self::pop_result(&self.availability);
        Box::pin(async move { result })
    }
}

struct Harness {
    pool: SqlitePool,
    owner_id: i64,
}

async fn harness() -> Harness {
    let pool = db::connect("sqlite::memory:")
        .await
        .expect("test database should migrate");
    let owner_id = sqlx::query_scalar::<_, i64>(
        "INSERT INTO users (email, password_hash) VALUES ('archive@example.test', 'fixture-hash') RETURNING id",
    )
    .fetch_one(&pool)
    .await
    .expect("test owner should be inserted");
    Harness { pool, owner_id }
}

async fn create_project(pool: &SqlitePool, owner_id: i64, link: &str) -> i64 {
    sqlx::query_scalar::<_, i64>(
        "INSERT INTO projects (owner_id, name, link, created_at, updated_at) \
         VALUES (?, 'Archive test', ?, '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z') \
         RETURNING id",
    )
    .bind(owner_id)
    .bind(link)
    .fetch_one(pool)
    .await
    .expect("test project should be inserted")
}

async fn create_yarn(pool: &SqlitePool, owner_id: i64, link: &str) -> i64 {
    sqlx::query_scalar::<_, i64>(
        "INSERT INTO yarns (owner_id, name, link, created_at, updated_at) \
         VALUES (?, 'Archive yarn', ?, '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z') \
         RETURNING id",
    )
    .bind(owner_id)
    .bind(link)
    .fetch_one(pool)
    .await
    .expect("test yarn should be inserted")
}

fn client<T: WaybackTransport + 'static>(transport: Arc<T>) -> WaybackClient<T, FixtureResolver> {
    WaybackClient::with_transport_and_resolver(true, transport, Arc::new(FixtureResolver))
}

fn snapshot_url() -> Url {
    Url::parse("https://web.archive.org/web/20250101000000/https://ravelry.com/pattern/4")
        .expect("test snapshot URL should parse")
}

async fn job_state(pool: &SqlitePool, job_id: i64) -> (String, i64, bool, Option<String>) {
    sqlx::query_as(
        "SELECT state, attempt_count, save_accepted, last_error \
         FROM archive_jobs WHERE id = ?",
    )
    .bind(job_id)
    .fetch_one(pool)
    .await
    .expect("archive job should exist")
}

#[tokio::test]
async fn enqueue_is_owned_idempotent_and_marks_requested() {
    let app = harness().await;
    let project_id = create_project(&app.pool, app.owner_id, "https://ravelry.com/pattern/4").await;

    let first = enqueue(
        &app.pool,
        app.owner_id,
        ArchiveEntityKind::Project,
        project_id,
    )
    .await
    .expect("enqueue should succeed")
    .expect("owned project should have an archivable link");
    let second = enqueue(
        &app.pool,
        app.owner_id,
        ArchiveEntityKind::Project,
        project_id,
    )
    .await
    .expect("repeat enqueue should succeed")
    .expect("owned project should still have a link");
    assert_eq!(first.id, second.id);
    assert_eq!(second.state, ArchiveJobState::Pending);
    assert_eq!(
        sqlx::query_scalar::<_, i64>("SELECT count(*) FROM archive_jobs")
            .fetch_one(&app.pool)
            .await
            .expect("job count should be readable"),
        1
    );
    assert!(
        sqlx::query_scalar::<_, Option<String>>(
            "SELECT archive_requested_at FROM projects WHERE id = ?",
        )
        .bind(project_id)
        .fetch_one(&app.pool)
        .await
        .expect("request timestamp should be readable")
        .is_some()
    );

    let other_owner = sqlx::query_scalar::<_, i64>(
        "INSERT INTO users (email, password_hash) VALUES ('other-archive@example.test', 'fixture-hash') RETURNING id",
    )
    .fetch_one(&app.pool)
    .await
    .expect("other user should be inserted");
    assert!(
        enqueue(
            &app.pool,
            other_owner,
            ArchiveEntityKind::Project,
            project_id,
        )
        .await
        .expect("foreign enqueue should be rejected")
        .is_none()
    );
}

#[tokio::test]
async fn successful_job_updates_only_current_owned_entity() {
    let app = harness().await;
    let project_id = create_project(&app.pool, app.owner_id, "https://ravelry.com/pattern/4").await;
    let job = enqueue(
        &app.pool,
        app.owner_id,
        ArchiveEntityKind::Project,
        project_id,
    )
    .await
    .expect("enqueue should succeed")
    .expect("project should be queued");
    let transport = Arc::new(FixtureTransport::new(
        vec![Ok(Some(snapshot_url()))],
        vec![],
    ));

    assert_eq!(
        process_one(&app.pool, &client(transport.clone()))
            .await
            .expect("job should process"),
        ArchiveProcessResult::Succeeded {
            job_id: job.id,
            snapshot_url: snapshot_url().to_string(),
        }
    );
    let stored = sqlx::query_scalar::<_, Option<String>>(
        "SELECT link_archive FROM projects WHERE id = ? AND owner_id = ?",
    )
    .bind(project_id)
    .bind(app.owner_id)
    .fetch_one(&app.pool)
    .await
    .expect("archive link should be readable");
    assert_eq!(stored.as_deref(), Some(snapshot_url().as_str()));
    assert_eq!(job_state(&app.pool, job.id).await.0, "archived");
}

#[tokio::test]
async fn pending_job_checks_availability_later_without_resubmitting_save() {
    let app = harness().await;
    let project_id = create_project(&app.pool, app.owner_id, "https://ravelry.com/pattern/4").await;
    let job = enqueue(
        &app.pool,
        app.owner_id,
        ArchiveEntityKind::Project,
        project_id,
    )
    .await
    .expect("enqueue should succeed")
    .expect("project should be queued");
    let transport = Arc::new(FixtureTransport::new(
        vec![Ok(None)],
        vec![Ok(None), Ok(Some(snapshot_url()))],
    ));
    let client = client(transport.clone());

    assert_eq!(
        process_one(&app.pool, &client)
            .await
            .expect("initial save should process"),
        ArchiveProcessResult::Pending { job_id: job.id }
    );
    assert!(job_state(&app.pool, job.id).await.2);
    sqlx::query("UPDATE archive_jobs SET next_attempt_at = unixepoch() - 1 WHERE id = ?")
        .bind(job.id)
        .execute(&app.pool)
        .await
        .expect("pending job should become due");

    assert!(matches!(
        process_one(&app.pool, &client)
            .await
            .expect("availability retry should process"),
        ArchiveProcessResult::Succeeded { job_id, .. } if job_id == job.id
    ));
    assert_eq!(
        *transport
            .calls
            .lock()
            .expect("fixture call list should not be poisoned"),
        vec!["save", "availability", "availability"]
    );
}

#[tokio::test]
async fn expired_lease_recovers_after_restart_without_repeating_accepted_save() {
    let app = harness().await;
    let yarn_id = create_yarn(&app.pool, app.owner_id, "https://ravelry.com/pattern/4").await;
    let job = enqueue(&app.pool, app.owner_id, ArchiveEntityKind::Yarn, yarn_id)
        .await
        .expect("enqueue should succeed")
        .expect("yarn should be queued");
    sqlx::query(
        "UPDATE archive_jobs SET state = 'running', save_accepted = 1, \
                lease_until = unixepoch() - 1 WHERE id = ?",
    )
    .bind(job.id)
    .execute(&app.pool)
    .await
    .expect("lease should expire");
    let transport = Arc::new(FixtureTransport::new(vec![], vec![Ok(None)]));

    assert_eq!(
        process_one(&app.pool, &client(transport.clone()))
            .await
            .expect("expired lease should be reclaimed"),
        ArchiveProcessResult::Pending { job_id: job.id }
    );
    assert_eq!(
        *transport
            .calls
            .lock()
            .expect("fixture call list should not be poisoned"),
        vec!["availability"]
    );
    assert_eq!(job_state(&app.pool, job.id).await.0, "pending");
}

#[tokio::test]
async fn changed_or_deleted_entities_are_superseded_without_archive_calls() {
    let app = harness().await;
    let project_id = create_project(&app.pool, app.owner_id, "https://ravelry.com/pattern/4").await;
    let project_job = enqueue(
        &app.pool,
        app.owner_id,
        ArchiveEntityKind::Project,
        project_id,
    )
    .await
    .expect("enqueue should succeed")
    .expect("project should be queued");
    sqlx::query("UPDATE projects SET link = 'https://ravelry.com/pattern/5' WHERE id = ?")
        .bind(project_id)
        .execute(&app.pool)
        .await
        .expect("source should change");

    let transport = Arc::new(FixtureTransport::new(vec![], vec![]));
    assert_eq!(
        process_one(&app.pool, &client(transport.clone()))
            .await
            .expect("stale project job should be superseded"),
        ArchiveProcessResult::Superseded {
            job_id: project_job.id
        }
    );
    assert_eq!(job_state(&app.pool, project_job.id).await.0, "superseded");
    assert!(
        transport
            .calls
            .lock()
            .expect("fixture call list should not be poisoned")
            .is_empty()
    );

    let yarn_id = create_yarn(&app.pool, app.owner_id, "https://ravelry.com/pattern/6").await;
    let yarn_job = enqueue(&app.pool, app.owner_id, ArchiveEntityKind::Yarn, yarn_id)
        .await
        .expect("enqueue should succeed")
        .expect("yarn should be queued");
    sqlx::query("DELETE FROM yarns WHERE id = ? AND owner_id = ?")
        .bind(yarn_id)
        .bind(app.owner_id)
        .execute(&app.pool)
        .await
        .expect("yarn should be deleted");
    assert_eq!(
        process_one(&app.pool, &client(transport.clone()))
            .await
            .expect("deleted yarn job should be superseded"),
        ArchiveProcessResult::Superseded {
            job_id: yarn_job.id
        }
    );
}

#[tokio::test]
async fn transient_errors_schedule_bounded_retry_and_redacted_error_code() {
    let app = harness().await;
    let project_id = create_project(&app.pool, app.owner_id, "https://ravelry.com/pattern/4").await;
    let job = enqueue(
        &app.pool,
        app.owner_id,
        ArchiveEntityKind::Project,
        project_id,
    )
    .await
    .expect("enqueue should succeed")
    .expect("project should be queued");
    let transport = Arc::new(FixtureTransport::new(
        vec![Err(WaybackError::TransportFailed)],
        vec![Err(WaybackError::TransportFailed)],
    ));

    assert_eq!(
        process_one(&app.pool, &client(transport))
            .await
            .expect("transport failure should be recorded"),
        ArchiveProcessResult::RetryScheduled {
            job_id: job.id,
            attempt: 1,
        }
    );
    let (state, attempts, save_accepted, error) = job_state(&app.pool, job.id).await;
    assert_eq!(state, "pending");
    assert_eq!(attempts, 1);
    assert!(!save_accepted);
    assert_eq!(error.as_deref(), Some("wayback_transport_failed"));
}

#[tokio::test]
async fn disabled_client_does_not_claim_or_call_archive() {
    let app = harness().await;
    let project_id = create_project(&app.pool, app.owner_id, "https://ravelry.com/pattern/4").await;
    let job = enqueue(
        &app.pool,
        app.owner_id,
        ArchiveEntityKind::Project,
        project_id,
    )
    .await
    .expect("enqueue should succeed")
    .expect("project should be queued");
    let transport = Arc::new(FixtureTransport::new(vec![], vec![]));
    let client = WaybackClient::with_transport_and_resolver(
        false,
        transport.clone(),
        Arc::new(FixtureResolver),
    );

    assert_eq!(
        process_one(&app.pool, &client)
            .await
            .expect("disabled worker should be idle"),
        ArchiveProcessResult::Idle
    );
    assert_eq!(job_state(&app.pool, job.id).await.0, "pending");
    assert!(
        transport
            .calls
            .lock()
            .expect("fixture call list should not be poisoned")
            .is_empty()
    );
}

#[tokio::test]
async fn availability_errors_preserve_accepted_save_and_never_resubmit_it() {
    let app = harness().await;
    let project_id = create_project(&app.pool, app.owner_id, "https://ravelry.com/pattern/4").await;
    let job = enqueue(
        &app.pool,
        app.owner_id,
        ArchiveEntityKind::Project,
        project_id,
    )
    .await
    .expect("enqueue should succeed")
    .expect("project should be queued");
    let transport = Arc::new(FixtureTransport::new(
        vec![Ok(None)],
        vec![
            Ok(None),
            Err(WaybackError::TransportFailed),
            Ok(Some(snapshot_url())),
        ],
    ));
    let client = client(transport.clone());

    assert_eq!(
        process_one(&app.pool, &client)
            .await
            .expect("initial save should process"),
        ArchiveProcessResult::Pending { job_id: job.id }
    );
    sqlx::query("UPDATE archive_jobs SET next_attempt_at = unixepoch() - 1 WHERE id = ?")
        .bind(job.id)
        .execute(&app.pool)
        .await
        .expect("pending job should become due");
    assert_eq!(
        process_one(&app.pool, &client)
            .await
            .expect("availability failure should be retried"),
        ArchiveProcessResult::RetryScheduled {
            job_id: job.id,
            attempt: 2,
        }
    );
    assert!(job_state(&app.pool, job.id).await.2);
    sqlx::query("UPDATE archive_jobs SET next_attempt_at = unixepoch() - 1 WHERE id = ?")
        .bind(job.id)
        .execute(&app.pool)
        .await
        .expect("availability retry should become due");
    assert!(matches!(
        process_one(&app.pool, &client)
            .await
            .expect("availability should find snapshot"),
        ArchiveProcessResult::Succeeded { job_id, .. } if job_id == job.id
    ));
    assert_eq!(
        *transport
            .calls
            .lock()
            .expect("fixture call list should not be poisoned"),
        vec!["save", "availability", "availability", "availability"]
    );
}

#[tokio::test]
async fn pending_polling_exhaustion_marks_the_entity_failed() {
    let app = harness().await;
    let project_id = create_project(&app.pool, app.owner_id, "https://ravelry.com/pattern/4").await;
    let job = enqueue(
        &app.pool,
        app.owner_id,
        ArchiveEntityKind::Project,
        project_id,
    )
    .await
    .expect("enqueue should succeed")
    .expect("project should be queued");
    sqlx::query("UPDATE archive_jobs SET attempt_count = 11, save_accepted = 1 WHERE id = ?")
        .bind(job.id)
        .execute(&app.pool)
        .await
        .expect("pending poll count should be set to its final attempt");
    let transport = Arc::new(FixtureTransport::new(vec![], vec![Ok(None)]));

    assert_eq!(
        process_one(&app.pool, &client(transport))
            .await
            .expect("final pending poll should complete"),
        ArchiveProcessResult::Failed {
            job_id: job.id,
            error: WaybackError::SaveFailed,
        }
    );
    let (state, attempts, accepted, error) = job_state(&app.pool, job.id).await;
    assert_eq!(state, "failed");
    assert_eq!(attempts, 12);
    assert!(accepted);
    assert_eq!(error.as_deref(), Some("pending_expired"));
    assert!(
        sqlx::query_scalar::<_, bool>("SELECT archive_failed FROM projects WHERE id = ?")
            .bind(project_id)
            .fetch_one(&app.pool)
            .await
            .expect("project failure flag should be readable")
    );
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn reclaimed_lease_cannot_be_completed_by_the_stale_worker() {
    let app = harness().await;
    let project_id = create_project(&app.pool, app.owner_id, "https://ravelry.com/pattern/4").await;
    let job = enqueue(
        &app.pool,
        app.owner_id,
        ArchiveEntityKind::Project,
        project_id,
    )
    .await
    .expect("enqueue should succeed")
    .expect("project should be queued");
    let first_snapshot =
        Url::parse("https://web.archive.org/web/20250101000000/https://ravelry.com/pattern/4")
            .expect("first snapshot should parse");
    let second_snapshot =
        Url::parse("https://web.archive.org/web/20250102000000/https://ravelry.com/pattern/4")
            .expect("second snapshot should parse");
    let transport = Arc::new(ReclaimTransport {
        calls: AtomicUsize::new(0),
        first_started: Semaphore::new(0),
        release_first: Semaphore::new(0),
        first_snapshot,
        second_snapshot: second_snapshot.clone(),
    });
    let client = client(transport.clone());
    let pool = app.pool.clone();
    let first_client = client.clone();
    let first_worker = tokio::spawn(async move { process_one(&pool, &first_client).await });
    transport
        .first_started
        .acquire()
        .await
        .expect("first save should start")
        .forget();
    sqlx::query("UPDATE archive_jobs SET lease_until = unixepoch() - 1 WHERE id = ?")
        .bind(job.id)
        .execute(&app.pool)
        .await
        .expect("first worker lease should expire");

    assert!(matches!(
        process_one(&app.pool, &client)
            .await
            .expect("second worker should reclaim and finish"),
        ArchiveProcessResult::Succeeded { job_id, .. } if job_id == job.id
    ));
    transport.release_first.add_permits(1);
    assert_eq!(
        first_worker
            .await
            .expect("first worker task should complete")
            .expect("stale worker should report a typed result"),
        ArchiveProcessResult::LeaseLost { job_id: job.id }
    );

    assert_eq!(job_state(&app.pool, job.id).await.0, "archived");
    assert_eq!(
        sqlx::query_scalar::<_, Option<String>>("SELECT link_archive FROM projects WHERE id = ?",)
            .bind(project_id)
            .fetch_one(&app.pool)
            .await
            .expect("current archive link should be readable")
            .as_deref(),
        Some(second_snapshot.as_str())
    );
    assert_eq!(transport.calls.load(Ordering::SeqCst), 2);
    assert_eq!(
        sqlx::query_scalar::<_, i64>("SELECT claim_token FROM archive_jobs WHERE id = ?")
            .bind(job.id)
            .fetch_one(&app.pool)
            .await
            .expect("monotonic claim token should be readable"),
        2
    );
}
