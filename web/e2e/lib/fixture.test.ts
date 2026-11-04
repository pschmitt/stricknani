// Ports of ci/tests/test_rust_fixture.py.
import {
  chmodSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  statSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  FixtureError,
  OWNER_FILE,
  REPO_ROOT,
  fixtureDeps,
  loadFixtureEnvironment,
  runCmd,
  serverEnvironment,
  startFixture,
  stopFixture,
  writePrivateJson,
  type FixtureProcess,
} from "./fixture";

let tmp: string;

beforeEach(() => {
  tmp = mkdtempSync(join(tmpdir(), "stricknani-fixture-test-"));
});

afterEach(() => {
  vi.restoreAllMocks();
  rmSync(tmp, { recursive: true, force: true });
});

function executable(name: string, content = "server"): string {
  const path = join(tmp, name);
  writeFileSync(path, content);
  chmodSync(path, 0o700);
  return path;
}

describe("private files", () => {
  it("writes private JSON outside the checkout with mode 0600", () => {
    const fixture = join(tmp, "private", "fixture.json");

    writePrivateJson(fixture, { value: "hidden" });

    expect(JSON.parse(readFileSync(fixture, "utf-8"))).toEqual({
      value: "hidden",
    });
    expect(statSync(fixture).mode & 0o777).toBe(0o600);
  });

  it("rejects checkout paths", () => {
    expect(() =>
      writePrivateJson(join(REPO_ROOT, "fixture.json"), { value: "hidden" }),
    ).toThrow(/outside the repository/);
    expect(() =>
      writePrivateJson(join(REPO_ROOT, "fixture.json"), { value: "hidden" }),
    ).toThrow(FixtureError);
  });

  it("loads the fixture environment privately without mutating process.env", () => {
    const fixture = join(tmp, "fixture.json");
    const values = {
      url: "http://127.0.0.1:12345",
      email: "fixture@example.invalid",
      password: "test-password-value",
    };
    writePrivateJson(fixture, values);
    const before = { ...process.env };

    const environment = loadFixtureEnvironment(fixture);

    expect(environment).toEqual({
      STRICKNANI_RUST_TEST_URL: values.url,
      STRICKNANI_RUST_TEST_EMAIL: values.email,
      STRICKNANI_RUST_TEST_PASSWORD: values.password,
    });
    expect({ ...process.env }).toEqual(before);
    vi.stubEnv("STRICKNANI_RUST_TEST_PASSWORD", "ambient-value");
    expect(loadFixtureEnvironment(fixture).STRICKNANI_RUST_TEST_PASSWORD).toBe(
      values.password,
    );
    vi.unstubAllEnvs();
  });

  it("rejects a group or world readable fixture file", () => {
    const fixture = join(tmp, "fixture.json");
    writeFileSync(fixture, "{}");
    chmodSync(fixture, 0o644);

    expect(() => loadFixtureEnvironment(fixture)).toThrow(
      /private regular file/,
    );
  });
});

describe("serverEnvironment", () => {
  it("adds WEB_ROOT only when explicit and requires a directory", () => {
    const runtime = join(tmp, "runtime");
    mkdirSync(runtime);
    const webRoot = join(tmp, "web-build");
    mkdirSync(webRoot);

    const defaults = serverEnvironment(runtime, 32123);
    const web = serverEnvironment(runtime, 32123, webRoot);
    const signup = serverEnvironment(runtime, 32123, undefined, true);

    expect(defaults).not.toHaveProperty("WEB_ROOT");
    expect(defaults).not.toHaveProperty("SIGNUP_ENABLED");
    expect(defaults).not.toHaveProperty("TRUSTED_HOSTS");
    expect(web.WEB_ROOT).toBe(resolve(webRoot));
    expect(signup.SIGNUP_ENABLED).toBe("true");
    expect(web).not.toHaveProperty("TRUSTED_HOSTS");
    expect(web.STRICKNANI_DATABASE_URL).toBe(defaults.STRICKNANI_DATABASE_URL);
    expect(() =>
      serverEnvironment(runtime, 32123, join(tmp, "missing")),
    ).toThrow(/web root must be an existing directory/);
  });

  it("adds only the exact synthetic browser host", () => {
    const runtime = join(tmp, "runtime");
    mkdirSync(runtime);

    const environment = serverEnvironment(
      runtime,
      32123,
      undefined,
      false,
      "stricknani-device-012345abcdef.localhost:18762",
    );

    expect(environment.BIND_PORT).toBe("32123");
    expect(environment.TRUSTED_HOSTS).toBe(
      "localhost,127.0.0.1,[::1],stricknani-device-012345abcdef.localhost:18762",
    );
  });

  it.each([
    "stricknani-device-012345abcdef.localhost:0",
    "stricknani-device-012345abcdef.localhost:65536",
    "stricknani-device-012345abcdef.localhost:018762",
    "stricknani-device-012345abcde.localhost:18762",
    "stricknani-device-012345ABCDEf.localhost:18762",
    "stricknani-device-012345abcdef.localhost:18762,*.localhost",
    "user@stricknani-device-012345abcdef.localhost:18762",
    "stricknani-device-012345abcdef.localhost:18762/path",
    "other-012345abcdef.localhost:18762",
    "*.localhost:18762",
    " stricknani-device-012345abcdef.localhost:18762",
  ])("rejects the noncanonical browser authority %j", (authority) => {
    const runtime = join(tmp, "runtime");
    mkdirSync(runtime);

    expect(() =>
      serverEnvironment(runtime, 32123, undefined, false, authority),
    ).toThrow(/browser authority must be a synthetic device hostname/);
  });
});

describe("startFixture", () => {
  it("rejects a browser authority before runtime or user creation", async () => {
    const binary = executable("stricknani-server");
    const runtime = join(tmp, "runtime");
    const createUser = vi.spyOn(fixtureDeps, "runCmd");

    await expect(
      startFixture({
        binary,
        tempDir: runtime,
        fixtureFile: join(tmp, "fixture.json"),
        stateFile: join(tmp, "state.json"),
        allowedBrowserAuthority: "localhost:18762",
      }),
    ).rejects.toThrow(/browser authority must be a synthetic device hostname/);

    expect(existsSync(runtime)).toBe(false);
    expect(existsSync(join(tmp, "fixture.json"))).toBe(false);
    expect(existsSync(join(tmp, "state.json"))).toBe(false);
    expect(createUser).not.toHaveBeenCalled();
  });

  it.each([false, true])(
    "masks generated credentials only in Actions (GITHUB_ACTIONS=%s)",
    async (githubActions) => {
      const binary = executable(
        "stricknani-server",
        "fixture binary placeholder",
      );
      const fixtureServer = executable(
        "optional-fixture",
        "fixture companion placeholder",
      );
      const fixtureFile = join(tmp, "private", "fixture.json");
      const stateFile = join(tmp, "private", "state.json");

      const spawned: { argv: string[]; env: Record<string, string> }[] = [];
      const createUserArgs: string[][] = [];
      vi.stubEnv("GITHUB_ACTIONS", String(githubActions));
      const written: string[] = [];
      vi.spyOn(process.stdout, "write").mockImplementation(((
        chunk: string | Uint8Array,
      ) => {
        written.push(String(chunk));
        return true;
      }) as typeof process.stdout.write);
      vi.spyOn(fixtureDeps, "spawnServer").mockImplementation(
        (argv, env): FixtureProcess => {
          spawned.push({ argv, env });
          return { pid: 424242, exited: () => false };
        },
      );
      vi.spyOn(fixtureDeps, "unusedPort").mockResolvedValue(56789);
      vi.spyOn(fixtureDeps, "processStartTime").mockReturnValue("start");
      vi.spyOn(fixtureDeps, "safeTerminate").mockResolvedValue(undefined);
      vi.spyOn(fixtureDeps, "waitForHealth").mockResolvedValue(undefined);
      vi.spyOn(fixtureDeps, "runCmd").mockImplementation((args) => {
        createUserArgs.push([...args]);
      });

      await startFixture({
        binary,
        tempDir: join(tmp, "runtime"),
        fixtureFile,
        stateFile,
        serverBinary: fixtureServer,
        ocrMode: "failure",
        signupEnabled: true,
      });

      const runtime = (
        JSON.parse(readFileSync(stateFile, "utf-8")) as { runtime: string }
      ).runtime;
      const resolvedBinary = resolve(binary);
      expect(spawned).toHaveLength(1);
      expect(spawned[0]?.argv).toEqual([
        resolve(fixtureServer),
        "--document-worker",
        resolvedBinary,
        "--fixture-runtime",
        runtime,
        "--ocr-mode",
        "failure",
        "--archive-mode",
        "success",
      ]);
      expect(createUserArgs).toHaveLength(1);
      expect(createUserArgs[0]?.slice(0, 2)).toEqual([
        resolvedBinary,
        "create-user",
      ]);
      expect(spawned[0]?.env.SIGNUP_ENABLED).toBe("true");

      const credentials = JSON.parse(readFileSync(fixtureFile, "utf-8")) as {
        email: string;
        password: string;
      };
      const output = written.join("");
      if (githubActions) {
        expect(output).toContain(`::add-mask::${credentials.email}\n`);
        expect(output).toContain(`::add-mask::${credentials.password}\n`);
      } else {
        expect(output).not.toContain(credentials.email);
        expect(output).not.toContain(credentials.password);
        expect(output).toBe("");
      }
    },
  );

  it("rejects a non-executable companion", async () => {
    const binary = executable("stricknani-server");
    const companion = join(tmp, "not-executable");
    writeFileSync(companion, "companion");
    chmodSync(companion, 0o600);

    await expect(
      startFixture({
        binary,
        serverBinary: companion,
        tempDir: join(tmp, "runtime"),
        fixtureFile: join(tmp, "fixture.json"),
        stateFile: join(tmp, "state.json"),
      }),
    ).rejects.toThrow(/fixture server binary is not executable/);
  });

  it.each(["ocrMode", "archiveMode"] as const)(
    "rejects an invalid service mode for %s",
    async (option) => {
      const binary = executable("stricknani-server");
      const companion = executable("fixture-server", "fixture server");

      await expect(
        startFixture({
          binary,
          serverBinary: companion,
          tempDir: join(tmp, "runtime"),
          fixtureFile: join(tmp, "fixture.json"),
          stateFile: join(tmp, "state.json"),
          [option]: "arbitrary-argument",
        } as unknown as Parameters<typeof startFixture>[0]),
      ).rejects.toThrow(/fixture mode must be/);
    },
  );
});

describe("runCmd", () => {
  it("sends the password on stdin and suppresses output", () => {
    const captured: { command?: string; args?: readonly string[] } & Record<
      string,
      unknown
    > = {};
    vi.spyOn(fixtureDeps, "spawnSync").mockImplementation(((
      command: string,
      args: readonly string[],
      options: Record<string, unknown>,
    ) => {
      captured.command = command;
      captured.args = args;
      Object.assign(captured, options);
      return { status: 0, error: undefined };
    }) as unknown as typeof fixtureDeps.spawnSync);

    runCmd(["server", "create-user", "fixture@example.invalid"], {
      env: { PATH: "/bin" },
      inputBytes: Buffer.from("test-password-value\n"),
    });

    expect(String(captured.input)).toBe("test-password-value\n");
    expect(captured.stdio).toEqual(["pipe", "ignore", "ignore"]);
    expect(captured.args).not.toContain("test-password-value");
    expect(captured.args).not.toContain("test-password-value\n");
  });

  it("reports failures without command output", () => {
    vi.spyOn(fixtureDeps, "spawnSync").mockReturnValue({
      status: 1,
    } as unknown as ReturnType<typeof fixtureDeps.spawnSync>);

    expect(() => runCmd(["server"], { env: {} })).toThrow(
      "fixture command failed",
    );
  });
});

describe("stopFixture", () => {
  it("rejects a mismatched owner without deleting files", async () => {
    const runtime = join(tmp, "runtime");
    mkdirSync(runtime);
    const marker = join(runtime, OWNER_FILE);
    writeFileSync(marker, "actual-owner", "ascii");
    const stateFile = join(tmp, "state.json");
    writeFileSync(
      stateFile,
      JSON.stringify({
        pid: 99999999,
        process_start: "1",
        runtime,
        fixture: join(tmp, "fixture.json"),
        owner: "different-owner",
      }),
    );
    chmodSync(stateFile, 0o600);
    const terminate = vi.spyOn(fixtureDeps, "safeTerminate");

    await expect(stopFixture(stateFile)).rejects.toThrow(/owner marker/);

    expect(readFileSync(marker, "ascii")).toBe("actual-owner");
    expect(existsSync(stateFile)).toBe(true);
    expect(terminate).not.toHaveBeenCalled();
  });
});
