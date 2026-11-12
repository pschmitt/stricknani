import assert from "node:assert/strict";
import test from "node:test";
import {
  defaultFetch,
  verifyProducerMetadata,
  waitForProducer,
} from "./wait-for-nix-producer.mjs";

const sha = (digit) => digit.repeat(40);
const workflowId = "build.yaml";
const repo = "owner/repo";

function fixture({
  event = "push",
  sourceSha = sha("a"),
  prHeadSha = "",
  conclusion = "success",
  artifact = true,
  runId = 321,
  includeArm = true,
  extraJobs = [],
  jobs: providedJobs,
} = {}) {
  const run = {
    id: runId,
    run_attempt: 1,
    event,
    head_branch: event === "push" ? "main" : "feature",
    head_sha: event === "pull_request" ? prHeadSha : sourceSha,
    status: "completed",
    conclusion: "success",
    created_at: "2026-11-12T12:00:00Z",
    pull_requests:
      event === "pull_request"
        ? [{ number: 17, head: { sha: prHeadSha } }]
        : [],
  };
  let pending = 0;
  const jobs = providedJobs ?? [
    { name: "nix", run_attempt: 1, status: "completed", conclusion },
    ...(includeArm
      ? [
          {
            name: "nix-aarch64 / build",
            run_attempt: 1,
            status: "completed",
            conclusion,
          },
        ]
      : []),
    ...extraJobs,
  ];
  const fetcher = async (url) => {
    const path = new URL(url).pathname;
    if (path.endsWith(`/workflows/${workflowId}/runs`)) {
      assert.equal(
        new URL(url).searchParams.get("head_sha"),
        event === "pull_request" ? prHeadSha : sourceSha,
      );
      return Response.json({ workflow_runs: pending-- > 0 ? [] : [run] });
    }
    if (path.endsWith(`/runs/${run.id}`)) return Response.json(run);
    if (path.endsWith(`/runs/${run.id}/jobs`)) {
      assert.equal(new URL(url).searchParams.get("filter"), "all");
      return Response.json({ total_count: jobs.length, jobs });
    }
    if (path.endsWith(`/runs/${run.id}/artifacts`)) {
      return Response.json({
        artifacts: artifact
          ? [
              {
                id: 77,
                name: "nix-producer-metadata",
                expired: false,
                size_in_bytes: 512,
              },
            ]
          : [],
      });
    }
    throw new Error(`Unexpected mocked API path: ${path}`);
  };
  return {
    fetcher,
    run,
    jobs,
    setPending(count) {
      pending = count;
    },
  };
}

function env(overrides = {}) {
  return {
    GITHUB_REPOSITORY: repo,
    GITHUB_TOKEN: "test-token",
    PRODUCER_WORKFLOW: workflowId,
    SOURCE_SHA: sha("a"),
    GITHUB_EVENT_NAME: "push",
    REF_NAME: "main",
    REF_TYPE: "branch",
    PRODUCER_JOBS: "nix,nix-aarch64 / build",
    MAX_ATTEMPTS: "3",
    POLL_INTERVAL_MS: "1",
    ...overrides,
  };
}

test("waits for the matching Build run and exact required producer jobs", async () => {
  const api = fixture();
  api.setPending(1);
  let sleeps = 0;
  const result = await waitForProducer({
    env: env(),
    fetcher: api.fetcher,
    sleep: async () => sleeps++,
    log: () => {},
  });
  assert.deepEqual(result, {
    runId: 321,
    runAttempt: 1,
    sourceSha: sha("a"),
    prHeadSha: "",
  });
  assert.equal(sleeps, 1);
});

test("uses the captured Build job names without waiting for unrelated jobs", async () => {
  const api = fixture({
    runId: 43_271_574_173,
    extraJobs: [
      {
        name: "android / Assemble",
        status: "completed",
        conclusion: "success",
      },
      { name: "changes", status: "completed", conclusion: "success" },
      { name: "docker", status: "completed", conclusion: "failure" },
      { name: "e2e", status: "in_progress", conclusion: null },
      { name: "release", status: "queued", conclusion: null },
    ],
  });
  const result = await waitForProducer({
    env: env({ PRODUCER_JOBS: "nix,nix-aarch64 / build" }),
    fetcher: api.fetcher,
    sleep: async () => {},
    log: () => {},
  });
  assert.deepEqual(result, {
    runId: 43_271_574_173,
    runAttempt: 1,
    sourceSha: sha("a"),
    prHeadSha: "",
  });
});

test("reuses attempt 1 producer outputs during an attempt 2 validation-only rerun", async () => {
  const api = fixture({
    jobs: [
      {
        id: 101,
        name: "nix",
        run_attempt: 1,
        status: "completed",
        conclusion: "success",
      },
      {
        id: 102,
        name: "nix-aarch64 / build",
        run_attempt: 1,
        status: "completed",
        conclusion: "success",
      },
      {
        id: 103,
        name: "nix-checks",
        run_attempt: 2,
        status: "completed",
        conclusion: "success",
      },
    ],
  });
  api.run.run_attempt = 2;

  const result = await waitForProducer({
    env: env(),
    fetcher: api.fetcher,
    sleep: async () => {},
    log: () => {},
  });

  assert.equal(result.runId, 321);
  assert.equal(result.runAttempt, 1);
});

test("a newer failed producer attempt does not fall back to an older success", async () => {
  const api = fixture({
    jobs: [
      {
        id: 101,
        name: "nix",
        run_attempt: 1,
        status: "completed",
        conclusion: "success",
      },
      {
        id: 102,
        name: "nix",
        run_attempt: 2,
        status: "completed",
        conclusion: "failure",
      },
      {
        id: 103,
        name: "nix-aarch64 / build",
        run_attempt: 1,
        status: "completed",
        conclusion: "success",
      },
    ],
  });
  api.run.run_attempt = 2;

  await assert.rejects(
    waitForProducer({
      env: env(),
      fetcher: api.fetcher,
      sleep: async () => {},
      log: () => {},
    }),
    /producer job nix concluded failure/,
  );
});

test("finds a newer failed producer attempt on a later jobs page", async () => {
  const api = fixture();
  api.run.run_attempt = 2;
  const noisyJobs = Array.from({ length: 98 }, (_, index) => ({
    id: index + 1,
    name: `unrelated ${index}`,
    run_attempt: 1,
    status: "completed",
    conclusion: "success",
  }));
  const pageOne = [
    {
      id: 900,
      name: "nix",
      run_attempt: 1,
      status: "completed",
      conclusion: "success",
    },
    {
      id: 901,
      name: "nix-aarch64 / build",
      run_attempt: 1,
      status: "completed",
      conclusion: "success",
    },
    ...noisyJobs,
  ];
  const fetcher = async (url, token) => {
    const parsed = new URL(url);
    if (parsed.pathname.endsWith(`/runs/${api.run.id}/jobs`)) {
      const page = Number(parsed.searchParams.get("page"));
      const jobs =
        page === 1
          ? pageOne
          : [
              {
                id: 902,
                name: "nix",
                run_attempt: 2,
                status: "completed",
                conclusion: "failure",
              },
            ];
      return Response.json({ total_count: 101, jobs });
    }
    return api.fetcher(url, token);
  };

  await assert.rejects(
    waitForProducer({
      env: env(),
      fetcher,
      sleep: async () => {},
      log: () => {},
    }),
    /producer job nix concluded failure/,
  );
});

test("selects the latest producer attempt from unsorted all-attempt job results", async () => {
  const api = fixture({
    jobs: [
      {
        id: 102,
        name: "nix",
        run_attempt: 2,
        status: "completed",
        conclusion: "success",
      },
      {
        id: 101,
        name: "nix",
        run_attempt: 1,
        status: "completed",
        conclusion: "failure",
      },
      {
        id: 103,
        name: "nix-aarch64 / build",
        run_attempt: 1,
        status: "completed",
        conclusion: "success",
      },
    ],
  });
  api.run.run_attempt = 2;

  const result = await waitForProducer({
    env: env(),
    fetcher: api.fetcher,
    sleep: async () => {},
    log: () => {},
  });

  assert.equal(result.runAttempt, 2);
});

test("refreshes the pinned run before accepting a newly created producer attempt", async () => {
  const api = fixture({
    jobs: [
      {
        id: 101,
        name: "nix",
        run_attempt: 1,
        status: "in_progress",
        conclusion: null,
      },
      {
        id: 103,
        name: "nix-aarch64 / build",
        run_attempt: 1,
        status: "completed",
        conclusion: "success",
      },
    ],
  });
  api.run.status = "in_progress";
  api.run.conclusion = null;
  let sleeps = 0;
  const result = await waitForProducer({
    env: env(),
    fetcher: api.fetcher,
    sleep: async () => {
      sleeps += 1;
      api.run.run_attempt = 2;
      api.run.status = "completed";
      api.run.conclusion = "success";
      api.jobs.push({
        id: 102,
        name: "nix",
        run_attempt: 2,
        status: "completed",
        conclusion: "success",
      });
    },
    log: () => {},
  });

  assert.equal(sleeps, 1);
  assert.equal(result.runAttempt, 2);
});

test("fails immediately when a completed producer omitted a required job", async () => {
  const api = fixture({ includeArm: false });
  let sleeps = 0;
  await assert.rejects(
    waitForProducer({
      env: env(),
      fetcher: api.fetcher,
      sleep: async () => sleeps++,
      log: () => {},
    }),
    /completed Build producer is missing job nix-aarch64 \/ build/,
  );
  assert.equal(sleeps, 0);
});

test("PR selection uses PR head identity and metadata proves the merge source SHA", async () => {
  const head = sha("b");
  const merge = sha("c");
  const api = fixture({
    event: "pull_request",
    sourceSha: merge,
    prHeadSha: head,
  });
  const result = await waitForProducer({
    env: env({
      GITHUB_EVENT_NAME: "pull_request",
      SOURCE_SHA: merge,
      REF_NAME: "17/merge",
      REF_TYPE: "branch",
      PR_NUMBER: "17",
      PR_HEAD_SHA: head,
    }),
    fetcher: api.fetcher,
    sleep: async () => {},
    log: () => {},
  });
  assert.deepEqual(result, {
    runId: 321,
    runAttempt: 1,
    sourceSha: merge,
    prHeadSha: head,
  });
  assert.equal(
    verifyProducerMetadata(
      { source_sha: merge, pr_head_sha: head, run_id: 321, run_attempt: 1 },
      { sourceSha: merge, prHeadSha: head, runId: 321, runAttempt: 1 },
    ),
    true,
  );
});

test("does not select an old run whose mutable PR association now points at the new head", async () => {
  const head = sha("b");
  const merge = sha("c");
  const api = fixture({
    event: "pull_request",
    sourceSha: merge,
    prHeadSha: head,
  });
  const staleRun = {
    ...api.run,
    id: 123,
    head_sha: sha("d"),
    created_at: "2026-11-12T12:01:00Z",
    // GitHub can update this association after the PR gets a new commit.
    pull_requests: [{ number: 17, head: { sha: head } }],
  };
  const fetcher = async (url, token) => {
    if (new URL(url).pathname.endsWith(`/workflows/${workflowId}/runs`)) {
      assert.equal(new URL(url).searchParams.get("head_sha"), head);
      return Response.json({ workflow_runs: [staleRun, api.run] });
    }
    return api.fetcher(url, token);
  };
  const result = await waitForProducer({
    env: env({
      GITHUB_EVENT_NAME: "pull_request",
      SOURCE_SHA: merge,
      PR_NUMBER: "17",
      PR_HEAD_SHA: head,
    }),
    fetcher,
    sleep: async () => {},
    log: () => {},
  });
  assert.equal(result.runId, 321);
});

test("fails closed when downloaded metadata source SHA differs from the consumer source", () => {
  assert.throws(
    () =>
      verifyProducerMetadata(
        {
          source_sha: sha("d"),
          pr_head_sha: null,
          run_id: 321,
          run_attempt: 1,
        },
        { sourceSha: sha("a"), prHeadSha: "", runId: 321, runAttempt: 1 },
      ),
    /source SHA does not match/,
  );
});

test("rejects unexpected metadata fields and run identity", () => {
  const expected = {
    sourceSha: sha("a"),
    prHeadSha: "",
    runId: 321,
    runAttempt: 1,
  };
  assert.throws(
    () =>
      verifyProducerMetadata(
        {
          source_sha: sha("a"),
          pr_head_sha: null,
          run_id: 322,
          run_attempt: 1,
        },
        expected,
      ),
    /does not match its workflow run/,
  );
  assert.throws(
    () =>
      verifyProducerMetadata(
        {
          source_sha: sha("a"),
          pr_head_sha: null,
          run_id: 321,
          run_attempt: 1,
          extra: true,
        },
        expected,
      ),
    /unexpected shape/,
  );
});

test("rejects older metadata after a newer producer attempt succeeded", () => {
  assert.throws(
    () =>
      verifyProducerMetadata(
        {
          source_sha: sha("a"),
          pr_head_sha: null,
          run_id: 321,
          run_attempt: 1,
        },
        { sourceSha: sha("a"), prHeadSha: "", runId: 321, runAttempt: 2 },
      ),
    /does not match its workflow run/,
  );
});

test("sanitizes API fetch failures and applies a timeout signal", async () => {
  const previousFetch = globalThis.fetch;
  let signal;
  globalThis.fetch = async (_url, options) => {
    signal = options.signal;
    throw new Error("sensitive response detail");
  };
  try {
    await assert.rejects(
      defaultFetch("https://api.github.com/test", "not-logged"),
      /failed or timed out/,
    );
    assert.ok(signal instanceof AbortSignal);
  } finally {
    globalThis.fetch = previousFetch;
  }
});

test("fails when a required producer job failed", async () => {
  const api = fixture({ conclusion: "failure" });
  await assert.rejects(
    waitForProducer({
      env: env(),
      fetcher: api.fetcher,
      sleep: async () => {},
      log: () => {},
    }),
    /producer job nix concluded failure/,
  );
});

test("allows standalone tags when no Build producer exists", async () => {
  const fetcher = async () => Response.json({ workflow_runs: [] });
  const result = await waitForProducer({
    env: env({
      GITHUB_EVENT_NAME: "push",
      REF_NAME: "v1.2.3",
      REF_TYPE: "tag",
      ALLOW_STANDALONE: "true",
    }),
    fetcher,
    sleep: async () =>
      assert.fail("standalone runs must not wait for a producer"),
    log: () => {},
  });
  assert.deepEqual(result, { standalone: true });
});

test("allows standalone tags when historical producer metadata expired", async () => {
  const api = fixture({ artifact: false });
  const result = await waitForProducer({
    env: env({
      GITHUB_EVENT_NAME: "push",
      REF_NAME: "v1.2.3",
      REF_TYPE: "tag",
      ALLOW_STANDALONE: "true",
    }),
    fetcher: api.fetcher,
    sleep: async () => {},
    log: () => {},
  });
  assert.deepEqual(result, { standalone: true });
});

test("bounds retries when a matching producer run never appears", async () => {
  let requests = 0;
  let sleeps = 0;
  const fetcher = async () => {
    requests += 1;
    return Response.json({ workflow_runs: [] });
  };
  await assert.rejects(
    waitForProducer({
      env: env(),
      fetcher,
      sleep: async () => sleeps++,
      log: () => {},
    }),
    /No matching Build producer run appeared before the wait deadline/,
  );
  assert.equal(requests, 3);
  assert.equal(sleeps, 2);
});

test("requires the x86 producer job used for metadata attempt identity", async () => {
  await assert.rejects(
    waitForProducer({
      env: env({ PRODUCER_JOBS: "nix-aarch64 / build" }),
      fetcher: async () => assert.fail("must validate producer names first"),
      sleep: async () => {},
      log: () => {},
    }),
    /must include the nix producer job/,
  );
});

test("reuses a shared producer when validation alone was retried", async () => {
  const api = fixture({
    jobs: [
      {
        name: "nix",
        run_attempt: 1,
        status: "completed",
        conclusion: "failure",
      },
      {
        name: "nix / build",
        run_attempt: 1,
        status: "completed",
        conclusion: "success",
      },
      {
        name: "nix-checks / checks",
        run_attempt: 2,
        status: "completed",
        conclusion: "success",
      },
      {
        name: "nix-aarch64 / build",
        run_attempt: 1,
        status: "completed",
        conclusion: "success",
      },
      {
        name: "nix-aarch64-checks / checks",
        run_attempt: 2,
        status: "completed",
        conclusion: "success",
      },
    ],
  });
  api.run.run_attempt = 2;
  const result = await waitForProducer({
    env: env({
      PRODUCER_JOB: "nix / build",
      PRODUCER_JOBS:
        "nix / build,nix-checks / checks,nix-aarch64 / build,nix-aarch64-checks / checks",
    }),
    fetcher: api.fetcher,
    sleep: async () => assert.fail("all required jobs already passed"),
    log: () => {},
  });
  assert.equal(result.runAttempt, 1);
});

test("requires the configured shared producer in the validation gate", async () => {
  await assert.rejects(
    waitForProducer({
      env: env({ PRODUCER_JOB: "nix / build" }),
      fetcher: async () => assert.fail("must validate producer names first"),
      sleep: async () => {},
      log: () => {},
    }),
    /must include the nix \/ build producer job/,
  );
});
