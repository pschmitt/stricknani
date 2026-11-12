#!/usr/bin/env node

import { readFileSync, statSync } from "node:fs";

const API = "https://api.github.com";
const DEFAULT_ATTEMPTS = 90;
const DEFAULT_INTERVAL_MS = 30_000;

function fail(message) {
  throw new Error(message);
}

function requiredEnv(env, key) {
  const value = env[key]?.trim();
  if (!value) fail(`Missing required environment variable ${key}.`);
  return value;
}

function parsePositiveInteger(value, fallback, label) {
  if (value === undefined || value === "") return fallback;
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed) || parsed < 1)
    fail(`${label} must be a positive integer.`);
  return parsed;
}

function isPullRequestRun(run, prNumber, headSha) {
  return (
    run.event === "pull_request" &&
    Array.isArray(run.pull_requests) &&
    run.pull_requests.some(
      (pr) => pr.number === prNumber && pr.head?.sha === headSha,
    )
  );
}

function chooseRun(runs, context) {
  const ordered = runs.toSorted(
    (a, b) => Date.parse(b.created_at) - Date.parse(a.created_at),
  );
  if (context.event === "pull_request") {
    const match = ordered.find(
      (run) =>
        run.head_sha === context.prHeadSha &&
        isPullRequestRun(run, context.prNumber, context.prHeadSha),
    );
    return match ?? null;
  }

  if (context.event === "push" && context.refType === "branch") {
    return (
      ordered.find(
        (run) =>
          run.event === "push" &&
          run.head_branch === context.refName &&
          run.head_sha === context.sourceSha,
      ) ?? null
    );
  }

  // Tags and manual runs are standalone-capable. Reuse a same-source main push or
  // explicit Build dispatch if one exists, but don't wait for a workflow that wasn't triggered.
  return (
    ordered.find(
      (run) =>
        run.head_sha === context.sourceSha &&
        ["push", "workflow_dispatch"].includes(run.event),
    ) ?? null
  );
}

function currentJobStates(jobs, requiredNames, run) {
  const producerFinished = run.status === "completed" || run.conclusion != null;
  const states = new Map(jobs.map((job) => [job.name, job]));
  for (const name of requiredNames) {
    const job = states.get(name);
    if (!job) {
      return producerFinished
        ? {
            ready: false,
            failed: true,
            missing: true,
            reason: `completed Build producer is missing job ${name}`,
          }
        : {
            ready: false,
            failed: false,
            missing: true,
            reason: `waiting for producer job ${name}`,
          };
    }
    if (job.conclusion && job.conclusion !== "success") {
      return {
        ready: false,
        failed: true,
        reason: `producer job ${name} concluded ${job.conclusion}`,
      };
    }
    if (job.status !== "completed") {
      return producerFinished
        ? {
            ready: false,
            failed: true,
            reason: `completed Build producer has unfinished job ${name}`,
          }
        : {
            ready: false,
            failed: false,
            reason: `producer job ${name} is ${job.status}`,
          };
    }
    if (job.conclusion !== "success")
      return {
        ready: false,
        failed: true,
        reason: `producer job ${name} has no successful conclusion`,
      };
  }
  return { ready: true, failed: false };
}

export async function defaultFetch(url, token) {
  try {
    return await fetch(url, {
      headers: {
        Accept: "application/vnd.github+json",
        Authorization: `Bearer ${token}`,
        "X-GitHub-Api-Version": "2022-11-28",
      },
      redirect: "follow",
      signal: AbortSignal.timeout(30_000),
    });
  } catch {
    fail("GitHub Actions API request failed or timed out.");
  }
}

async function responseJson(response, label) {
  if (!response.ok) fail(`${label} returned HTTP ${response.status}.`);
  try {
    return await response.json();
  } catch {
    fail(`${label} returned invalid JSON.`);
  }
}

async function apiJson(fetcher, token, url, label) {
  return responseJson(await fetcher(url, token), label);
}

async function getProducerRun(fetcher, token, repo, runId) {
  return apiJson(
    fetcher,
    token,
    `${API}/repos/${repo}/actions/runs/${runId}`,
    "Producer run status lookup",
  );
}

async function hasProducerMetadata(fetcher, token, repo, run) {
  const artifacts = await apiJson(
    fetcher,
    token,
    `${API}/repos/${repo}/actions/runs/${run.id}/artifacts?per_page=100`,
    "Producer artifact lookup",
  );
  const matches = (artifacts.artifacts ?? []).filter(
    (artifact) =>
      artifact.name === "nix-producer-metadata" && !artifact.expired,
  );
  if (matches.length !== 1) return null;
  if (
    !Number.isSafeInteger(matches[0].size_in_bytes) ||
    matches[0].size_in_bytes > 32_768
  ) {
    fail("Producer metadata artifact has an invalid size.");
  }
  return true;
}

export function verifyProducerMetadata(
  metadata,
  { sourceSha, prHeadSha, runId, runAttempt },
) {
  const keys = Object.keys(metadata ?? {}).sort();
  if (keys.join(",") !== "pr_head_sha,run_attempt,run_id,source_sha")
    fail("Producer metadata has an unexpected shape.");
  if (
    metadata.run_id !== Number(runId) ||
    metadata.run_attempt !== Number(runAttempt)
  ) {
    fail("Producer metadata does not match its workflow run.");
  }
  if (
    typeof metadata.source_sha !== "string" ||
    !/^[0-9a-f]{40}$/.test(metadata.source_sha)
  ) {
    fail("Producer metadata has no valid source SHA.");
  }
  if (metadata.source_sha !== sourceSha)
    fail(
      "Build producer source SHA does not match this workflow's source SHA.",
    );
  const expectedPrHeadSha = prHeadSha || null;
  if (metadata.pr_head_sha !== expectedPrHeadSha)
    fail("Build producer pull request head SHA does not match this workflow.");
  return true;
}

export async function waitForProducer({
  env = process.env,
  fetcher = defaultFetch,
  sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
  log = console.log,
} = {}) {
  const repo = requiredEnv(env, "GITHUB_REPOSITORY");
  const token = requiredEnv(env, "GITHUB_TOKEN");
  const workflow = requiredEnv(env, "PRODUCER_WORKFLOW");
  const sourceSha = requiredEnv(env, "SOURCE_SHA");
  const event = requiredEnv(env, "GITHUB_EVENT_NAME");
  const refName = env.REF_NAME ?? "";
  const refType = env.REF_TYPE ?? "";
  const prNumber = Number(env.PR_NUMBER ?? "");
  const prHeadSha = env.PR_HEAD_SHA ?? "";
  const requiredNames = requiredEnv(env, "PRODUCER_JOBS")
    .split(",")
    .map((name) => name.trim())
    .filter(Boolean);
  const standalone = env.ALLOW_STANDALONE === "true";
  const attempts = parsePositiveInteger(
    env.MAX_ATTEMPTS,
    DEFAULT_ATTEMPTS,
    "MAX_ATTEMPTS",
  );
  const intervalMs = parsePositiveInteger(
    env.POLL_INTERVAL_MS,
    DEFAULT_INTERVAL_MS,
    "POLL_INTERVAL_MS",
  );
  const runUrl = `${API}/repos/${repo}/actions/workflows/${encodeURIComponent(workflow)}/runs`;
  const context = { event, sourceSha, refName, refType, prNumber, prHeadSha };
  const standaloneAllowed =
    standalone &&
    (event === "workflow_dispatch" || (event === "push" && refType === "tag"));

  if (
    event === "pull_request" &&
    (!Number.isSafeInteger(prNumber) || prNumber < 1 || !prHeadSha)
  ) {
    fail("Pull request producer lookup requires PR_NUMBER and PR_HEAD_SHA.");
  }

  let run = null;
  for (let attempt = 1; attempt <= attempts; attempt += 1) {
    if (!run) {
      const eventFilters =
        event === "pull_request"
          ? ["pull_request"]
          : event === "push" && refType === "branch"
            ? ["push"]
            : ["push", "workflow_dispatch"];
      const responses = await Promise.all(
        eventFilters.map((eventFilter) => {
          const query = new URLSearchParams({
            event: eventFilter,
            per_page: "100",
          });
          if (event === "push" && refType === "branch")
            query.set("branch", refName);
          query.set(
            "head_sha",
            event === "pull_request" ? prHeadSha : sourceSha,
          );
          return apiJson(
            fetcher,
            token,
            `${runUrl}?${query}`,
            "Producer run lookup",
          );
        }),
      );
      const runs = responses.flatMap(
        (response) => response.workflow_runs ?? [],
      );
      run = chooseRun(runs ?? [], context);
      if (!run) {
        if (standaloneAllowed) {
          log(
            "No matching Build producer exists; continuing as a standalone run.",
          );
          return { standalone: true };
        }
        if (attempt === attempts)
          fail(
            "No matching Build producer run appeared before the wait deadline.",
          );
        log(`Waiting for a matching Build producer (${attempt}/${attempts}).`);
        await sleep(intervalMs);
        continue;
      }
    }

    const jobsResponse = await apiJson(
      fetcher,
      token,
      `${API}/repos/${repo}/actions/runs/${run.id}/jobs?per_page=100`,
      "Producer job lookup",
    );
    let states = currentJobStates(jobsResponse.jobs ?? [], requiredNames, run);
    if (states.missing && !states.failed) {
      run = await getProducerRun(fetcher, token, repo, run.id);
      states = currentJobStates(jobsResponse.jobs ?? [], requiredNames, run);
    }
    if (states.failed) fail(states.reason);
    if (states.ready) {
      const metadataExists = await hasProducerMetadata(
        fetcher,
        token,
        repo,
        run,
      );
      if (metadataExists)
        return {
          runId: run.id,
          runAttempt: run.run_attempt,
          sourceSha,
          prHeadSha,
        };
      if (standaloneAllowed) {
        log(
          "Build producer metadata is unavailable; continuing as a standalone run.",
        );
        return { standalone: true };
      }
    }

    if (attempt === attempts) {
      fail(
        states.ready
          ? "Build producer metadata did not appear before the wait deadline."
          : "Build producer jobs did not finish before the wait deadline.",
      );
    }
    log(`Waiting for Build producer run ${run.id} (${attempt}/${attempts}).`);
    await sleep(intervalMs);
  }

  fail("Build producer wait ended unexpectedly.");
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const main = async () => {
    if (process.argv[2] === "--verify") {
      const file = requiredEnv(process.env, "PRODUCER_METADATA_FILE");
      let metadata;
      try {
        if (statSync(file).size > 32_768)
          fail("Downloaded producer metadata is too large.");
        metadata = JSON.parse(readFileSync(file, "utf8"));
      } catch {
        fail("Downloaded producer metadata JSON is invalid.");
      }
      verifyProducerMetadata(metadata, {
        sourceSha: requiredEnv(process.env, "SOURCE_SHA"),
        prHeadSha: process.env.PR_HEAD_SHA ?? "",
        runId: requiredEnv(process.env, "PRODUCER_RUN_ID"),
        runAttempt: requiredEnv(process.env, "PRODUCER_RUN_ATTEMPT"),
      });
      console.log("Verified Build producer metadata source identity.");
      return;
    }
    const result = await waitForProducer();
    const output = process.env.GITHUB_OUTPUT;
    if (output) {
      const { appendFileSync } = await import("node:fs");
      if (result.standalone) appendFileSync(output, "standalone=true\n");
      else {
        appendFileSync(
          output,
          `standalone=false\nproducer_run_id=${result.runId}\nproducer_run_attempt=${result.runAttempt}\n`,
        );
      }
    }
    if (result.runId) console.log(`Using producer run ${result.runId}.`);
  };
  main().catch((error) => {
    console.error(error.message);
    process.exitCode = 1;
  });
}
