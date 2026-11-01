//! Durable, owner-scoped jobs for optional Wayback submissions.
//!
//! Claiming and persistence use short SQLite statements/transactions. The
//! archive HTTP calls always happen after the claim has committed.

use sqlx::{FromRow, SqlitePool};

use crate::imports::DnsResolver;
use crate::wayback::{WaybackClient, WaybackError, WaybackOutcome, WaybackTransport};

const LEASE_SECONDS: i64 = 90;
const MAX_FAILURE_ATTEMPTS: u32 = 5;
// At the six-hour backoff cap this bounds accepted-but-unavailable snapshots
// to roughly two days of polling, after which the entity is marked failed.
const MAX_PENDING_ATTEMPTS: u32 = 12;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ArchiveEntityKind {
    Project,
    Yarn,
}

impl ArchiveEntityKind {
    const fn as_str(self) -> &'static str {
        match self {
            Self::Project => "project",
            Self::Yarn => "yarn",
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ArchiveJobState {
    Pending,
    Running,
    Archived,
    Failed,
    Superseded,
}

impl TryFrom<&str> for ArchiveJobState {
    type Error = ArchiveJobError;

    fn try_from(value: &str) -> Result<Self, Self::Error> {
        match value {
            "pending" => Ok(Self::Pending),
            "running" => Ok(Self::Running),
            "archived" => Ok(Self::Archived),
            "failed" => Ok(Self::Failed),
            "superseded" => Ok(Self::Superseded),
            _ => Err(ArchiveJobError::InvalidState),
        }
    }
}

#[derive(Debug, thiserror::Error)]
pub enum ArchiveJobError {
    #[error("archive job database operation failed")]
    Database(#[source] sqlx::Error),
    #[error("archive job has an invalid state")]
    InvalidState,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct EnqueuedArchiveJob {
    pub id: i64,
    pub state: ArchiveJobState,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum ArchiveProcessResult {
    Idle,
    Pending { job_id: i64 },
    Succeeded { job_id: i64, snapshot_url: String },
    RetryScheduled { job_id: i64, attempt: u32 },
    Failed { job_id: i64, error: WaybackError },
    Superseded { job_id: i64 },
    LeaseLost { job_id: i64 },
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum Completion {
    Applied,
    LeaseLost,
    Superseded,
}

#[derive(Debug, FromRow)]
struct ArchiveJobRow {
    id: i64,
    owner_id: i64,
    entity_type: String,
    entity_id: i64,
    source_url: String,
    attempt_count: i64,
    claim_token: i64,
    save_accepted: bool,
}

/// Enqueue the current source link only if it belongs to an active owner.
/// Existing pending/running jobs are returned idempotently; failed or
/// superseded jobs are reset for an explicit user retry.
pub async fn enqueue(
    pool: &SqlitePool,
    owner_id: i64,
    kind: ArchiveEntityKind,
    entity_id: i64,
) -> Result<Option<EnqueuedArchiveJob>, ArchiveJobError> {
    let mut transaction = pool.begin().await.map_err(ArchiveJobError::Database)?;
    let enqueued = enqueue_in_transaction(&mut transaction, owner_id, kind, entity_id).await?;
    transaction
        .commit()
        .await
        .map_err(ArchiveJobError::Database)?;
    Ok(enqueued)
}

/// Enqueue within a caller-owned transaction. Use this from entity create and
/// update transactions to commit the domain mutation and durable job together.
pub async fn enqueue_in_transaction(
    connection: &mut sqlx::SqliteConnection,
    owner_id: i64,
    kind: ArchiveEntityKind,
    entity_id: i64,
) -> Result<Option<EnqueuedArchiveJob>, ArchiveJobError> {
    let source_url = current_source_url(connection, owner_id, kind, entity_id)
        .await
        .map_err(ArchiveJobError::Database)?;
    let Some(source_url) = source_url.filter(|url| !url.trim().is_empty()) else {
        return Ok(None);
    };

    let row = sqlx::query_as::<_, (i64, String)>(
        "INSERT INTO archive_jobs \
         (owner_id, entity_type, entity_id, source_url, state, next_attempt_at) \
         VALUES (?, ?, ?, ?, 'pending', unixepoch()) \
         ON CONFLICT(entity_type, entity_id, source_url) DO UPDATE SET \
             state = CASE WHEN archive_jobs.state IN ('failed', 'superseded') \
                          THEN 'pending' ELSE archive_jobs.state END, \
             attempt_count = CASE WHEN archive_jobs.state IN ('failed', 'superseded') \
                                  THEN 0 ELSE archive_jobs.attempt_count END, \
             save_accepted = CASE WHEN archive_jobs.state IN ('failed', 'superseded') \
                                  THEN 0 ELSE archive_jobs.save_accepted END, \
             next_attempt_at = CASE WHEN archive_jobs.state IN ('failed', 'superseded') \
                                    THEN unixepoch() ELSE archive_jobs.next_attempt_at END, \
             lease_until = CASE WHEN archive_jobs.state IN ('failed', 'superseded') \
                                THEN NULL ELSE archive_jobs.lease_until END, \
             last_error = CASE WHEN archive_jobs.state IN ('failed', 'superseded') \
                               THEN NULL ELSE archive_jobs.last_error END, \
             updated_at = unixepoch() \
         WHERE archive_jobs.owner_id = excluded.owner_id \
         RETURNING id, state",
    )
    .bind(owner_id)
    .bind(kind.as_str())
    .bind(entity_id)
    .bind(&source_url)
    .fetch_optional(&mut *connection)
    .await
    .map_err(ArchiveJobError::Database)?;
    let Some((job_id, state)) = row else {
        return Ok(None);
    };
    let state = ArchiveJobState::try_from(state.as_str())?;

    if state != ArchiveJobState::Archived {
        set_requested(connection, owner_id, kind, entity_id, &source_url)
            .await
            .map_err(ArchiveJobError::Database)?;
    }
    Ok(Some(EnqueuedArchiveJob { id: job_id, state }))
}

async fn current_source_url(
    connection: &mut sqlx::SqliteConnection,
    owner_id: i64,
    kind: ArchiveEntityKind,
    entity_id: i64,
) -> Result<Option<String>, sqlx::Error> {
    let row = match kind {
        ArchiveEntityKind::Project => {
            sqlx::query_scalar::<_, Option<String>>(
                "SELECT p.link FROM projects AS p \
                 JOIN users AS u ON u.id = p.owner_id \
                 WHERE p.id = ? AND p.owner_id = ? AND u.active = 1",
            )
            .bind(entity_id)
            .bind(owner_id)
            .fetch_optional(&mut *connection)
            .await?
        }
        ArchiveEntityKind::Yarn => {
            sqlx::query_scalar::<_, Option<String>>(
                "SELECT y.link FROM yarns AS y \
                 JOIN users AS u ON u.id = y.owner_id \
                 WHERE y.id = ? AND y.owner_id = ? AND u.active = 1",
            )
            .bind(entity_id)
            .bind(owner_id)
            .fetch_optional(&mut *connection)
            .await?
        }
    };
    Ok(row.flatten())
}

async fn set_requested(
    connection: &mut sqlx::SqliteConnection,
    owner_id: i64,
    kind: ArchiveEntityKind,
    entity_id: i64,
    source_url: &str,
) -> Result<(), sqlx::Error> {
    let query = match kind {
        ArchiveEntityKind::Project => {
            "UPDATE projects SET archive_requested_at = strftime('%Y-%m-%dT%H:%M:%fZ','now'), \
                    archive_failed = 0 \
             WHERE id = ? AND owner_id = ? AND link = ?"
        }
        ArchiveEntityKind::Yarn => {
            "UPDATE yarns SET archive_requested_at = strftime('%Y-%m-%dT%H:%M:%fZ','now'), \
                    archive_failed = 0 \
             WHERE id = ? AND owner_id = ? AND link = ?"
        }
    };
    sqlx::query(query)
        .bind(entity_id)
        .bind(owner_id)
        .bind(source_url)
        .execute(connection)
        .await?;
    Ok(())
}

/// Claim and process at most one due job. A single atomic UPDATE claims the
/// row; the network operation starts only after that statement has committed.
pub async fn process_one<T, R>(
    pool: &SqlitePool,
    client: &WaybackClient<T, R>,
) -> Result<ArchiveProcessResult, ArchiveJobError>
where
    T: WaybackTransport + 'static,
    R: DnsResolver + Send + Sync + 'static,
{
    if !client.is_enabled() {
        return Ok(ArchiveProcessResult::Idle);
    }
    let Some(job) = claim_next(pool).await? else {
        return Ok(ArchiveProcessResult::Idle);
    };
    let kind = match job.entity_type.as_str() {
        "project" => ArchiveEntityKind::Project,
        "yarn" => ArchiveEntityKind::Yarn,
        _ => return Err(ArchiveJobError::InvalidState),
    };
    if !source_is_current(pool, &job, kind).await? {
        return Ok(match mark_superseded(pool, &job).await? {
            Completion::Applied | Completion::Superseded => {
                ArchiveProcessResult::Superseded { job_id: job.id }
            }
            Completion::LeaseLost => ArchiveProcessResult::LeaseLost { job_id: job.id },
        });
    }

    let outcome = if job.save_accepted {
        client.check_availability(&job.source_url).await
    } else {
        client.archive(&job.source_url).await
    };
    match outcome {
        WaybackOutcome::Success { snapshot_url } => {
            match persist_success(pool, &job, kind, &snapshot_url).await? {
                Completion::Applied => Ok(ArchiveProcessResult::Succeeded {
                    job_id: job.id,
                    snapshot_url: snapshot_url.to_string(),
                }),
                Completion::Superseded => Ok(ArchiveProcessResult::Superseded { job_id: job.id }),
                Completion::LeaseLost => Ok(ArchiveProcessResult::LeaseLost { job_id: job.id }),
            }
        }
        WaybackOutcome::Pending => match schedule_pending(pool, &job, kind).await? {
            Completion::Applied if job.attempt_count as u32 >= MAX_PENDING_ATTEMPTS => {
                Ok(ArchiveProcessResult::Failed {
                    job_id: job.id,
                    error: WaybackError::SaveFailed,
                })
            }
            Completion::Applied => Ok(ArchiveProcessResult::Pending { job_id: job.id }),
            Completion::Superseded => Ok(ArchiveProcessResult::Superseded { job_id: job.id }),
            Completion::LeaseLost => Ok(ArchiveProcessResult::LeaseLost { job_id: job.id }),
        },
        WaybackOutcome::Failure { error } => {
            let completion = persist_failure(pool, &job, kind, error).await?;
            let terminal = !is_retryable(error) || job.attempt_count as u32 >= MAX_FAILURE_ATTEMPTS;
            match completion {
                Completion::LeaseLost => Ok(ArchiveProcessResult::LeaseLost { job_id: job.id }),
                Completion::Superseded => Ok(ArchiveProcessResult::Superseded { job_id: job.id }),
                Completion::Applied if terminal => Ok(ArchiveProcessResult::Failed {
                    job_id: job.id,
                    error,
                }),
                Completion::Applied => Ok(ArchiveProcessResult::RetryScheduled {
                    job_id: job.id,
                    attempt: job.attempt_count as u32,
                }),
            }
        }
    }
}

async fn claim_next(pool: &SqlitePool) -> Result<Option<ArchiveJobRow>, ArchiveJobError> {
    sqlx::query(
        "UPDATE archive_jobs SET state = 'pending', lease_until = NULL, \
                next_attempt_at = unixepoch(), last_error = 'lease_expired', \
                updated_at = unixepoch() \
         WHERE state = 'running' AND lease_until <= unixepoch()",
    )
    .execute(pool)
    .await
    .map_err(ArchiveJobError::Database)?;

    sqlx::query_as::<_, ArchiveJobRow>(
        "UPDATE archive_jobs SET state = 'running', \
                attempt_count = attempt_count + 1, \
                claim_token = claim_token + 1, \
                lease_until = unixepoch() + ?, updated_at = unixepoch() \
         WHERE id = ( \
             SELECT id FROM archive_jobs \
             WHERE state = 'pending' AND next_attempt_at <= unixepoch() \
             ORDER BY next_attempt_at, id LIMIT 1 \
         ) \
         AND state = 'pending' \
         RETURNING id, owner_id, entity_type, entity_id, source_url, attempt_count, claim_token, save_accepted",
    )
    .bind(LEASE_SECONDS)
    .fetch_optional(pool)
    .await
    .map_err(ArchiveJobError::Database)
}

async fn source_is_current(
    pool: &SqlitePool,
    job: &ArchiveJobRow,
    kind: ArchiveEntityKind,
) -> Result<bool, ArchiveJobError> {
    let query = match kind {
        ArchiveEntityKind::Project => {
            "SELECT EXISTS(SELECT 1 FROM projects AS e \
             JOIN users AS u ON u.id = e.owner_id \
             WHERE e.id = ? AND e.owner_id = ? AND e.link = ? AND u.active = 1)"
        }
        ArchiveEntityKind::Yarn => {
            "SELECT EXISTS(SELECT 1 FROM yarns AS e \
             JOIN users AS u ON u.id = e.owner_id \
             WHERE e.id = ? AND e.owner_id = ? AND e.link = ? AND u.active = 1)"
        }
    };
    sqlx::query_scalar::<_, bool>(query)
        .bind(job.entity_id)
        .bind(job.owner_id)
        .bind(&job.source_url)
        .fetch_one(pool)
        .await
        .map_err(ArchiveJobError::Database)
}

async fn persist_success(
    pool: &SqlitePool,
    job: &ArchiveJobRow,
    kind: ArchiveEntityKind,
    snapshot_url: &url::Url,
) -> Result<Completion, ArchiveJobError> {
    let mut transaction = pool.begin().await.map_err(ArchiveJobError::Database)?;
    let claimed = sqlx::query(
        "UPDATE archive_jobs SET state = 'archived', save_accepted = 1, \
                lease_until = NULL, last_error = NULL, updated_at = unixepoch() \
         WHERE id = ? AND state = 'running' AND claim_token = ?",
    )
    .bind(job.id)
    .bind(job.claim_token)
    .execute(&mut *transaction)
    .await
    .map_err(ArchiveJobError::Database)?
    .rows_affected()
        > 0;
    if !claimed {
        transaction
            .rollback()
            .await
            .map_err(ArchiveJobError::Database)?;
        return Ok(Completion::LeaseLost);
    }
    let update_entity = match kind {
        ArchiveEntityKind::Project => {
            "UPDATE projects SET link_archive = ?, archive_failed = 0 \
             WHERE id = ? AND owner_id = ? AND link = ? \
             AND EXISTS (SELECT 1 FROM users WHERE id = ? AND active = 1)"
        }
        ArchiveEntityKind::Yarn => {
            "UPDATE yarns SET link_archive = ?, archive_failed = 0 \
             WHERE id = ? AND owner_id = ? AND link = ? \
             AND EXISTS (SELECT 1 FROM users WHERE id = ? AND active = 1)"
        }
    };
    let updated = sqlx::query(update_entity)
        .bind(snapshot_url.as_str())
        .bind(job.entity_id)
        .bind(job.owner_id)
        .bind(&job.source_url)
        .bind(job.owner_id)
        .execute(&mut *transaction)
        .await
        .map_err(ArchiveJobError::Database)?
        .rows_affected()
        > 0;
    if !updated {
        sqlx::query(
            "UPDATE archive_jobs SET state = 'superseded', last_error = 'source_changed', \
                    updated_at = unixepoch() \
             WHERE id = ? AND state = 'archived' AND claim_token = ?",
        )
        .bind(job.id)
        .bind(job.claim_token)
        .execute(&mut *transaction)
        .await
        .map_err(ArchiveJobError::Database)?;
    }
    transaction
        .commit()
        .await
        .map_err(ArchiveJobError::Database)?;
    Ok(if updated {
        Completion::Applied
    } else {
        Completion::Superseded
    })
}

async fn schedule_pending(
    pool: &SqlitePool,
    job: &ArchiveJobRow,
    kind: ArchiveEntityKind,
) -> Result<Completion, ArchiveJobError> {
    let terminal = job.attempt_count as u32 >= MAX_PENDING_ATTEMPTS;
    let mut transaction = pool.begin().await.map_err(ArchiveJobError::Database)?;
    let updated = sqlx::query(
        "UPDATE archive_jobs SET state = ?, save_accepted = 1, \
                next_attempt_at = unixepoch() + ?, lease_until = NULL, \
                last_error = ?, updated_at = unixepoch() \
         WHERE id = ? AND state = 'running' AND claim_token = ?",
    )
    .bind(if terminal { "failed" } else { "pending" })
    .bind(if terminal {
        0
    } else {
        backoff_seconds(job.attempt_count as u32)
    })
    .bind(terminal.then_some("pending_expired"))
    .bind(job.id)
    .bind(job.claim_token)
    .execute(&mut *transaction)
    .await
    .map_err(ArchiveJobError::Database)?
    .rows_affected()
        > 0;
    if !updated {
        transaction
            .rollback()
            .await
            .map_err(ArchiveJobError::Database)?;
        return Ok(Completion::LeaseLost);
    }
    let entity_current = if terminal {
        set_entity_failed(&mut transaction, job, kind)
            .await
            .map_err(ArchiveJobError::Database)?
    } else {
        true
    };
    if !entity_current {
        sqlx::query(
            "UPDATE archive_jobs SET state = 'superseded', last_error = 'source_changed', \
                    updated_at = unixepoch() WHERE id = ? AND state = 'failed' AND claim_token = ?",
        )
        .bind(job.id)
        .bind(job.claim_token)
        .execute(&mut *transaction)
        .await
        .map_err(ArchiveJobError::Database)?;
    }
    transaction
        .commit()
        .await
        .map_err(ArchiveJobError::Database)?;
    Ok(if terminal && !entity_current {
        Completion::Superseded
    } else {
        Completion::Applied
    })
}

async fn persist_failure(
    pool: &SqlitePool,
    job: &ArchiveJobRow,
    kind: ArchiveEntityKind,
    error: WaybackError,
) -> Result<Completion, ArchiveJobError> {
    let terminal = !is_retryable(error) || job.attempt_count as u32 >= MAX_FAILURE_ATTEMPTS;
    let mut transaction = pool.begin().await.map_err(ArchiveJobError::Database)?;
    let updated = sqlx::query(
        "UPDATE archive_jobs SET state = ?, save_accepted = ?, \
                next_attempt_at = unixepoch() + ?, lease_until = NULL, \
                last_error = ?, updated_at = unixepoch() \
         WHERE id = ? AND state = 'running' AND claim_token = ?",
    )
    .bind(if terminal { "failed" } else { "pending" })
    .bind(job.save_accepted)
    .bind(if terminal {
        0
    } else {
        backoff_seconds(job.attempt_count as u32)
    })
    .bind(error.to_string())
    .bind(job.id)
    .bind(job.claim_token)
    .execute(&mut *transaction)
    .await
    .map_err(ArchiveJobError::Database)?
    .rows_affected()
        > 0;
    if !updated {
        transaction
            .rollback()
            .await
            .map_err(ArchiveJobError::Database)?;
        return Ok(Completion::LeaseLost);
    }
    let entity_current = if terminal {
        set_entity_failed(&mut transaction, job, kind)
            .await
            .map_err(ArchiveJobError::Database)?
    } else {
        true
    };
    if !entity_current {
        sqlx::query(
            "UPDATE archive_jobs SET state = 'superseded', last_error = 'source_changed', \
                    updated_at = unixepoch() WHERE id = ? AND state = 'failed' AND claim_token = ?",
        )
        .bind(job.id)
        .bind(job.claim_token)
        .execute(&mut *transaction)
        .await
        .map_err(ArchiveJobError::Database)?;
    }
    transaction
        .commit()
        .await
        .map_err(ArchiveJobError::Database)?;
    Ok(if terminal && !entity_current {
        Completion::Superseded
    } else {
        Completion::Applied
    })
}

async fn set_entity_failed(
    connection: &mut sqlx::SqliteConnection,
    job: &ArchiveJobRow,
    kind: ArchiveEntityKind,
) -> Result<bool, sqlx::Error> {
    let query = match kind {
        ArchiveEntityKind::Project => {
            "UPDATE projects SET archive_failed = 1 \
             WHERE id = ? AND owner_id = ? AND link = ? \
             AND EXISTS (SELECT 1 FROM users WHERE id = ? AND active = 1)"
        }
        ArchiveEntityKind::Yarn => {
            "UPDATE yarns SET archive_failed = 1 \
             WHERE id = ? AND owner_id = ? AND link = ? \
             AND EXISTS (SELECT 1 FROM users WHERE id = ? AND active = 1)"
        }
    };
    Ok(sqlx::query(query)
        .bind(job.entity_id)
        .bind(job.owner_id)
        .bind(&job.source_url)
        .bind(job.owner_id)
        .execute(connection)
        .await?
        .rows_affected()
        > 0)
}

async fn mark_superseded(
    pool: &SqlitePool,
    job: &ArchiveJobRow,
) -> Result<Completion, ArchiveJobError> {
    let updated = sqlx::query(
        "UPDATE archive_jobs SET state = 'superseded', lease_until = NULL, \
                last_error = 'source_changed', updated_at = unixepoch() \
         WHERE id = ? AND state = 'running' AND claim_token = ?",
    )
    .bind(job.id)
    .bind(job.claim_token)
    .execute(pool)
    .await
    .map_err(ArchiveJobError::Database)?
    .rows_affected()
        > 0;
    Ok(if updated {
        Completion::Applied
    } else {
        Completion::LeaseLost
    })
}

fn is_retryable(error: WaybackError) -> bool {
    matches!(
        error,
        WaybackError::SourceResolutionFailed
            | WaybackError::TimedOut
            | WaybackError::TransportFailed
            | WaybackError::RedirectRejected
            | WaybackError::SaveFailed
            | WaybackError::Internal
    )
}

const fn backoff_seconds(attempt: u32) -> i64 {
    match attempt {
        0 | 1 => 60,
        2 => 300,
        3 => 1_800,
        4 => 7_200,
        _ => 21_600,
    }
}
