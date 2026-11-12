#!/usr/bin/env node
import { spawn, spawnSync } from "node:child_process";
import {
  mkdirSync,
  mkdtempSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { homedir, tmpdir } from "node:os";
import path from "node:path";
import { createRequire } from "node:module";
import { Readable, Writable } from "node:stream";
import { pathToFileURL } from "node:url";

const SECRET_KEYS = [
  "DEEPSEEK_API_KEY",
  "GH_TOKEN",
  "GITHUB_PACKAGES_TOKEN",
];
const TOKEN_PATTERN =
  /\b(?:gh[pousr]_[A-Za-z0-9]{10,}|github_pat_[A-Za-z0-9_]{10,}|sk-[A-Za-z0-9_-]{10,}|xox[baprs]-[A-Za-z0-9-]{10,}|Bearer\s+[A-Za-z0-9._~+/=-]{10,})\b/g;
const ABSOLUTE_PATH_PATTERN =
  /(?<![A-Za-z0-9+.-])\/(?:Users|home|private|var|tmp|opt|Volumes|srv)\/[^\s)\]},>]+/g;
const MAX_DIAGNOSTIC_BYTES = 32 * 1024;

class ProbeError extends Error {
  constructor(code, message, detail = {}) {
    super(message);
    this.name = "ProbeError";
    this.code = code;
    this.detail = detail;
  }
}

function installBase() {
  return (
    process.env.DSH_INSTALL_ROOT ||
    path.join(homedir(), ".local", "share", "forgelet-dsh")
  );
}

function installRoot() {
  return path.join(installBase(), "current");
}

async function loadSdk() {
  const modules = path.join(installRoot(), "node_modules");
  const requireFromInstall = createRequire(path.join(modules, "package.json"));
  return import(
    pathToFileURL(requireFromInstall.resolve("@agentclientprotocol/sdk"))
  );
}

function activeSecrets(environment = process.env) {
  return SECRET_KEYS.map((name) => environment[name]).filter(
    (value) => typeof value === "string" && value.length > 0,
  );
}

export function redactSecrets(value, secrets = activeSecrets()) {
  let text = String(value ?? "");
  for (const secret of secrets) {
    text = text.split(secret).join("[redacted-secret]");
  }
  return text
    .replace(TOKEN_PATTERN, "[redacted-token]")
    .replace(ABSOLUTE_PATH_PATTERN, "[redacted-path]");
}

function boundedText(value, maximum = 500, secrets = activeSecrets()) {
  const text = redactSecrets(value, secrets).replace(/\s+/g, " ").trim();
  return text.length > maximum ? `${text.slice(0, maximum - 1)}…` : text;
}

function withTimeout(promise, milliseconds, code, label) {
  let timer;
  const timeout = new Promise((_, reject) => {
    timer = setTimeout(
      () => reject(new ProbeError(code, `${label} timed out`)),
      milliseconds,
    );
  });
  return Promise.race([promise, timeout]).finally(() => clearTimeout(timer));
}

function contentText(content) {
  return content?.type === "text" ? boundedText(content.text, 1000) : null;
}

export function normalizeUpdate(update) {
  switch (update?.sessionUpdate) {
    case "agent_message_chunk":
      return {
        type: "assistant_text",
        text: contentText(update.content),
      };
    case "agent_thought_chunk":
      return {
        type: "thought",
        text: contentText(update.content),
      };
    case "tool_call":
      return {
        type: "tool_start",
        tool_call_id: boundedText(update.toolCallId, 160),
        title: boundedText(update.title, 200),
        kind: boundedText(update.kind, 40),
        status: boundedText(update.status, 40),
      };
    case "tool_call_update":
      return {
        type: "tool_update",
        tool_call_id: boundedText(update.toolCallId, 160),
        title: update.title ? boundedText(update.title, 200) : undefined,
        status: update.status ? boundedText(update.status, 40) : undefined,
      };
    case "usage_update":
      return {
        type: "context_usage",
        used: update.used,
        size: update.size,
        cost: update.cost ?? null,
      };
    case "config_option_update":
      return {
        type: "config_option_update",
        config_options: Array.isArray(update.configOptions)
          ? update.configOptions
          : [],
      };
    default:
      return {
        type: "ignored",
        session_update: boundedText(update?.sessionUpdate || "unknown", 80),
      };
  }
}

export function effortChoices(configOptions) {
  const effort = configOptions?.find(
    (option) => option.id === "reasoning_effort",
  );
  if (!effort || !Array.isArray(effort.options)) return [];
  return effort.options
    .map((option) => String(option.value ?? ""))
    .filter((value) => value.length > 0);
}

function selectOption(options, kind) {
  return options?.find((option) => option.kind === kind);
}

function permissionResponse(options, decision) {
  if (decision === "cancel" || decision === "cancelled") {
    return { outcome: { outcome: "cancelled" } };
  }
  const kind = decision === "allow" ? "allow_once" : "reject_once";
  const option = selectOption(options, kind);
  if (!option) {
    return { outcome: { outcome: "cancelled" } };
  }
  return {
    outcome: {
      outcome: "selected",
      optionId: option.optionId,
    },
  };
}

class AcpProbe {
  constructor({ cwd, command, env, permissionHandler = null, timeoutMs = 120000 }) {
    this.cwd = path.resolve(cwd);
    this.command = command;
    this.env = env;
    this.permissionHandler = permissionHandler;
    this.timeoutMs = timeoutMs;
    this.updates = new Map();
    this.configs = new Map();
    this.inflight = new Set();
    this.permissions = [];
    this.stderr = "";
    this.closed = false;
  }

  async start(acp) {
    if (!Array.isArray(this.command) || this.command.length === 0) {
      throw new ProbeError("invalid_command", "ACP peer command is missing");
    }
    this.acp = acp;
    this.child = spawn(this.command[0], this.command.slice(1), {
      cwd: this.cwd,
      env: this.env,
      stdio: ["pipe", "pipe", "pipe"],
    });
    this.child.on("error", (error) => {
      this.transportError = new ProbeError(
        "transport_error",
        `ACP peer failed to start: ${boundedText(error.message, 200)}`,
      );
    });
    this.child.stderr.on("data", (chunk) => {
      if (this.stderr.length < MAX_DIAGNOSTIC_BYTES) {
        this.stderr += chunk.toString("utf8");
        if (this.stderr.length > MAX_DIAGNOSTIC_BYTES) {
          this.stderr = this.stderr.slice(0, MAX_DIAGNOSTIC_BYTES);
        }
      }
    });

    const stream = acp.ndJsonStream(
      Writable.toWeb(this.child.stdin),
      Readable.toWeb(this.child.stdout),
    );
    const permissionHandler = this.permissionHandler;
    const client = acp
      .client({ name: "forgelet-dsh-acp-probe" })
      .onNotification(acp.methods.client.session.update, ({ params }) => {
        const list = this.updates.get(params.sessionId) || [];
        list.push(normalizeUpdate(params.update));
        this.updates.set(params.sessionId, list);
      })
      .onRequest(
        acp.methods.client.session.requestPermission,
        async ({ params }) => {
          const offered = params.options.map((option) => ({
            option_id: boundedText(option.optionId, 120),
            kind: boundedText(option.kind, 40),
            name: boundedText(option.name, 120),
          }));
          let decision = "reject";
          if (permissionHandler !== null) {
            const selected = await permissionHandler(params);
            decision = selected || "reject";
          }
          const response = permissionResponse(params.options, decision);
          this.permissions.push({
            session_id: boundedText(params.sessionId, 160),
            tool_call_id: boundedText(params.toolCall?.toolCallId, 160),
            offered,
            decision,
            outcome: response.outcome.outcome,
            option_id: response.outcome.optionId || null,
          });
          return response;
        },
      );
    this.connection = client.connect(stream);
    this.ctx = this.connection.agent;
    return this;
  }

  async initialize() {
    try {
      this.initialized = await withTimeout(
        this.ctx.request(this.acp.methods.agent.initialize, {
          protocolVersion: this.acp.PROTOCOL_VERSION,
          clientCapabilities: {},
        }),
        this.timeoutMs,
        "handshake_timeout",
        "ACP initialize",
      );
      return this.initialized;
    } catch (error) {
      throw this.wrapError(error, "handshake_failed", "ACP initialize failed");
    }
  }

  async newSession(cwd = this.cwd) {
    try {
      const response = await withTimeout(
        this.ctx.request(this.acp.methods.agent.session.new, {
          cwd: path.resolve(cwd),
          mcpServers: [],
        }),
        this.timeoutMs,
        "session_timeout",
        "ACP session/new",
      );
      this.updates.set(response.sessionId, []);
      this.configs.set(response.sessionId, response.configOptions || []);
      return response;
    } catch (error) {
      throw this.wrapError(error, "session_failed", "ACP session/new failed");
    }
  }

  async setConfig(sessionId, configId, value) {
    try {
      const response = await withTimeout(
        this.ctx.request(this.acp.methods.agent.session.setConfigOption, {
          sessionId,
          configId,
          value,
        }),
        this.timeoutMs,
        "config_timeout",
        "ACP session/set_config_option",
      );
      const configOptions = response.configOptions || [];
      this.configs.set(sessionId, configOptions);
      return configOptions;
    } catch (error) {
      throw this.wrapError(
        error,
        "config_failed",
        "ACP configuration failed",
      );
    }
  }

  prompt(sessionId, text, { onAdmitted = null } = {}) {
    if (this.inflight.has(sessionId)) {
      throw new ProbeError(
        "prompt_in_flight",
        "The selected ACP session already has a prompt in flight",
      );
    }
    this.inflight.add(sessionId);
    const before = (this.updates.get(sessionId) || []).length;
    const selection = this.configs.get(sessionId) || [];
    const promise = (async () => {
      const started = Date.now();
      try {
        const request = this.ctx.request(this.acp.methods.agent.session.prompt, {
          sessionId,
          prompt: [{ type: "text", text }],
        });
        if (onAdmitted) await onAdmitted();
        const response = await withTimeout(
          request,
          this.timeoutMs,
          "prompt_timeout",
          "ACP session/prompt",
        );
        const events = (this.updates.get(sessionId) || []).slice(before);
        return {
          stop_reason: response.stopReason,
          elapsed_ms: Date.now() - started,
          selection_at_admission: selection,
          events,
        };
      } catch (error) {
        throw this.wrapError(error, "prompt_failed", "ACP prompt failed");
      } finally {
        this.inflight.delete(sessionId);
      }
    })();
    return promise;
  }

  async cancel(sessionId) {
    try {
      await this.ctx.notify(this.acp.methods.agent.session.cancel, {
        sessionId,
      });
    } catch (error) {
      throw this.wrapError(error, "cancel_failed", "ACP cancellation failed");
    }
  }

  async list(cwd = this.cwd) {
    try {
      return await withTimeout(
        this.ctx.request(this.acp.methods.agent.session.list, {
          cwd: path.resolve(cwd),
        }),
        this.timeoutMs,
        "list_timeout",
        "ACP session/list",
      );
    } catch (error) {
      throw this.wrapError(error, "list_failed", "ACP session/list failed");
    }
  }

  async resume(sessionId, cwd = this.cwd) {
    try {
      const response = await withTimeout(
        this.ctx.request(this.acp.methods.agent.session.resume, {
          sessionId,
          cwd: path.resolve(cwd),
          mcpServers: [],
        }),
        this.timeoutMs,
        "resume_timeout",
        "ACP session/resume",
      );
      this.updates.set(sessionId, []);
      this.configs.set(sessionId, response.configOptions || []);
      return response;
    } catch (error) {
      throw this.wrapError(error, "resume_failed", "ACP session/resume failed");
    }
  }

  async close(sessionId) {
    try {
      return await withTimeout(
        this.ctx.request(this.acp.methods.agent.session.close, { sessionId }),
        this.timeoutMs,
        "close_timeout",
        "ACP session/close",
      );
    } catch (error) {
      throw this.wrapError(error, "close_failed", "ACP session/close failed");
    }
  }

  async stop({ terminate = false } = {}) {
    if (this.closed) return;
    this.closed = true;
    try {
      if (!terminate) this.connection?.close();
      this.child?.stdin?.end();
    } catch {
      // The transport may already be gone.
    }
    if (terminate && this.child) {
      this.child.kill("SIGTERM");
    }
    if (this.child) {
      await Promise.race([
        new Promise((resolve) => this.child.once("exit", resolve)),
        new Promise((resolve) => setTimeout(resolve, 1500)),
      ]);
      if (this.child.exitCode === null && this.child.signalCode === null) {
        this.child.kill("SIGKILL");
      }
    }
  }

  diagnostics() {
    return {
      stderr: boundedText(this.stderr, 2000),
      exit_code: this.child?.exitCode ?? null,
      signal: this.child?.signalCode ?? null,
    };
  }

  wrapError(error, code, label) {
    if (error instanceof ProbeError) return error;
    const message = boundedText(error?.message || error, 400);
    return new ProbeError(code, `${label}: ${message}`, {
      diagnostics: this.diagnostics(),
    });
  }
}

async function waitForUpdate(probe, sessionId, predicate, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const events = probe.updates.get(sessionId) || [];
    const found = events.find(predicate);
    if (found) return found;
    await new Promise((resolve) => setTimeout(resolve, 20));
  }
  throw new ProbeError("update_timeout", "ACP update was not observed");
}

function minimalPeerEnv(extra = {}) {
  const home = mkdtempSync(path.join(tmpdir(), "forgelet-acp-home."));
  return {
    home,
    env: {
      PATH: process.env.PATH || "",
      HOME: home,
      LANG: process.env.LANG || "en_US.UTF-8",
      DSH_INSTALL_ROOT: installBase(),
      ...extra,
    },
  };
}

async function runPeerScenario(command, env, cwd, permissionHandler = null) {
  const acp = await loadSdk();
  const probe = await new AcpProbe({
    cwd,
    command,
    env,
    permissionHandler,
    timeoutMs: 5000,
  }).start(acp);
  try {
    const initialized = await probe.initialize();
    const first = await probe.newSession();
    const second = await probe.newSession();
    const efforts = effortChoices(first.configOptions);
    if (efforts.length < 2) {
      throw new ProbeError(
        "effort_missing",
        "ACP did not advertise at least two reasoning efforts",
      );
    }
    await probe.setConfig(first.sessionId, "reasoning_effort", efforts[0]);
    const low = await probe.prompt(
      first.sessionId,
      "read-only slow low effort task",
    );
    await probe.setConfig(first.sessionId, "reasoning_effort", efforts.at(-1));
    const high = await probe.prompt(
      first.sessionId,
      "implementation high effort task",
    );

    const concurrent = await Promise.all([
      probe.prompt(second.sessionId, "slow concurrent A"),
      probe.prompt(first.sessionId, "slow concurrent B"),
    ]);

    await probe.setConfig(first.sessionId, "reasoning_effort", efforts[0]);
    let pinResponse;
    const pinnedPrompt = probe.prompt(first.sessionId, "slow pin proof", {
      onAdmitted: async () => {
        await new Promise((resolve) => setTimeout(resolve, 90));
        await probe.setConfig(
          first.sessionId,
          "reasoning_effort",
          efforts.at(-1),
        );
      },
    });
    pinResponse = await pinnedPrompt;

    const permissionModes = ["allow", "reject", "cancel"];
    const permissionResults = [];
    for (const decision of permissionModes) {
      const modeProbe = await new AcpProbe({
        cwd,
        command,
        env,
        permissionHandler: async () => decision,
        timeoutMs: 5000,
      }).start(acp);
      try {
        await modeProbe.initialize();
        const session = await modeProbe.newSession();
        const response = await modeProbe.prompt(
          session.sessionId,
          `permission-${decision}`,
        );
        permissionResults.push({
          decision,
          permissions: modeProbe.permissions,
          stop_reason: response.stop_reason,
        });
      } finally {
        await modeProbe.stop({ terminate: true });
      }
    }

    const defaultPermissionProbe = await new AcpProbe({
      cwd,
      command,
      env,
      timeoutMs: 5000,
    }).start(acp);
    await defaultPermissionProbe.initialize();
    const defaultPermissionSession =
      await defaultPermissionProbe.newSession();
    await defaultPermissionProbe.prompt(
      defaultPermissionSession.sessionId,
      "permission-default",
    );
    const defaultPermission = {
      permissions: defaultPermissionProbe.permissions,
    };
    await defaultPermissionProbe.stop({ terminate: true });

    const cancellation = probe.prompt(
      first.sessionId,
      "cancel-slow-prompt",
    );
    await waitForUpdate(
      probe,
      first.sessionId,
      (event) => event.type === "tool_start",
      2000,
    );
    await probe.cancel(first.sessionId);
    const cancelled = await cancellation;
    const gitPrompt = await probe.prompt(
      first.sessionId,
      "git-commit bounded workspace write",
    );
    const workspaceCommit = git(cwd, "rev-parse", "HEAD");
    const workspaceFiles = git(
      cwd,
      "show",
      "--pretty=",
      "--name-only",
      "HEAD",
    )
      .split("\n")
      .filter(Boolean);
    const workspaceClean = git(cwd, "status", "--porcelain") === "";

    await probe.close(second.sessionId);
    const listed = await probe.list();
    const resumeId = first.sessionId;
    await probe.stop({ terminate: true });

    const restarted = await new AcpProbe({
      cwd,
      command,
      env,
      timeoutMs: 5000,
    }).start(acp);
    await restarted.initialize();
    const resumed = await restarted.resume(resumeId);
    const replayedBefore = (restarted.updates.get(resumeId) || []).length;
    const history = await restarted.prompt(
      resumeId,
      "history follow-up slow",
    );
    await restarted.close(resumeId);
    await restarted.stop({ terminate: true });

    return {
      initialized: {
        protocol_version: initialized.protocolVersion,
        agent_info: initialized.agentInfo,
        capabilities: initialized.agentCapabilities,
      },
      sessions: [first.sessionId, second.sessionId],
      efforts,
      low,
      high,
      concurrent: concurrent.map((item, index) => ({
        session_id: index === 0 ? second.sessionId : first.sessionId,
        stop_reason: item.stop_reason,
        events: item.events,
      })),
      pin: pinResponse,
      permissions: permissionResults,
      default_permission: defaultPermission,
      cancellation: cancelled,
      git: {
        commit: workspaceCommit,
        changed_files: workspaceFiles,
        status_clean: workspaceClean,
        prompt: gitPrompt,
      },
      listed,
      resumed: {
        config_options: resumed.configOptions,
        replayed_updates: replayedBefore,
        history,
      },
    };
  } finally {
    await probe.stop({ terminate: true });
  }
}

async function runFailureMode(fakePeer, baseEnv, cwd, mode) {
  const env = { ...baseEnv, FAKE_ACP_MODE: mode };
  const acp = await loadSdk();
  const probe = await new AcpProbe({
    cwd,
    command: [process.execPath, fakePeer],
    env,
    timeoutMs: 1500,
  }).start(acp);
  try {
    await probe.initialize();
    const session = await probe.newSession();
    await probe.prompt(session.sessionId, "transport prompt");
    return { failure_observed: false };
  } catch (error) {
    return {
      failure_observed: true,
      code: error.code,
      message: boundedText(error.message, 300),
    };
  } finally {
    await probe.stop({ terminate: true });
  }
}

export async function selfTest(fakePeer, root, evidenceFile) {
  const temp = minimalPeerEnv();
  const stateFile = path.join(temp.home, "fake-acp-state.json");
  temp.env.FAKE_ACP_STATE = stateFile;
  temp.env.FAKE_ACP_MODE = "normal";
  writeFileSync(path.join(root, "README.md"), "# Fake ACP Workspace\n");
  git(root, "init", "-q");
  git(root, "config", "user.name", "Fake ACP");
  git(root, "config", "user.email", "fake-acp@localhost");
  git(root, "add", "README.md");
  git(root, "commit", "-q", "-m", "fake fixture");
  const evidence = await runPeerScenario(
    [process.execPath, fakePeer],
    temp.env,
    root,
  );
  evidence.failure_modes = {};
  for (const mode of [
    "malformed",
    "refuse-initialize",
    "early-exit",
    "exit-at-prompt",
    "transport-loss",
  ]) {
    evidence.failure_modes[mode] = await runFailureMode(
      fakePeer,
      temp.env,
      root,
      mode,
    );
  }
  evidence.acp_adoption = {
    suitable: true,
    limitations: [
      "standard ACP does not expose a transcript replay surface",
      "the protocol does not label the pinned route in each update",
      "additional workspace directories are unsupported",
      "client filesystem and terminal capabilities are intentionally unused",
    ],
  };
  writeEvidence(evidenceFile, evidence);
  rmSync(temp.home, { recursive: true, force: true });
  return evidence;
}

function writeEvidence(file, evidence) {
  mkdirSync(path.dirname(path.resolve(file)), { recursive: true });
  const secrets = activeSecrets();
  const sanitize = (value) => {
    if (typeof value === "string") return redactSecrets(value, secrets);
    if (Array.isArray(value)) return value.map(sanitize);
    if (value && typeof value === "object") {
      return Object.fromEntries(
        Object.entries(value).map(([key, item]) => [key, sanitize(item)]),
      );
    }
    return value;
  };
  const text = JSON.stringify(sanitize(evidence), null, 2);
  writeFileSync(file, `${text}\n`, { mode: 0o600 });
}

function git(cwd, ...args) {
  const result = spawnSync("git", args, {
    cwd,
    encoding: "utf8",
    env: {
      PATH: process.env.PATH || "",
      HOME: cwd,
      GIT_CONFIG_NOSYSTEM: "1",
    },
  });
  if (result.status !== 0) {
    throw new ProbeError(
      "git_failed",
      boundedText(result.stderr || `git ${args.join(" ")} failed`, 300),
    );
  }
  return result.stdout.trim();
}

export async function liveTrial({ root, evidenceFile, dshBin = "dsh" }) {
  if (process.env.DSH_ACP_LIVE !== "1") {
    throw new ProbeError(
      "live_not_enabled",
      "Set DSH_ACP_LIVE=1 to run the live DeepSeek ACP trial",
    );
  }
  if (!process.env.DEEPSEEK_API_KEY) {
    throw new ProbeError(
      "missing_api_key",
      "DEEPSEEK_API_KEY is not present in the launch environment",
    );
  }
  const version = spawnSync(dshBin, ["--version"], {
    encoding: "utf8",
    env: {
      PATH: process.env.PATH || "",
      HOME: root,
      DSH_INSTALL_ROOT: installBase(),
    },
  });
  if (version.status !== 0) {
    throw new ProbeError("dsh_unavailable", "Pinned dsh cannot run");
  }
  const dshVersion = boundedText(version.stdout || version.stderr, 100);
  const workspace = mkdtempSync(path.join(tmpdir(), "forgelet-acp-workspace."));
  const dshHome = mkdtempSync(path.join(tmpdir(), "forgelet-acp-dsh-home."));
  writeFileSync(path.join(workspace, "README.md"), "# ACP Proof\n");
  git(workspace, "init", "-q");
  git(workspace, "config", "user.name", "ACP Probe");
  git(workspace, "config", "user.email", "acp-probe@localhost");
  git(workspace, "add", "README.md");
  git(workspace, "commit", "-q", "-m", "fixture");

  const env = {
    PATH: process.env.PATH || "",
    HOME: dshHome,
    DSH_HOME: dshHome,
    DSH_INSTALL_ROOT: installBase(),
    DEEPSEEK_API_KEY: process.env.DEEPSEEK_API_KEY,
    LANG: process.env.LANG || "en_US.UTF-8",
    GIT_CONFIG_NOSYSTEM: "1",
  };
  const permissionDecisions = [];
  const command = [dshBin, "--profile", "acp"];
  const acp = await loadSdk();
  const probe = await new AcpProbe({
    cwd: workspace,
    command,
    env,
    timeoutMs: Number(process.env.DSH_ACP_TIMEOUT_MS || 120000),
    permissionHandler: async () => {
      permissionDecisions.push("reject");
      return "reject";
    },
  }).start(acp);
  let sessionId;
  let evidence;
  try {
    const initialized = await probe.initialize();
    const session = await probe.newSession();
    sessionId = session.sessionId;
    const options = session.configOptions || [];
    const model = options.find((option) => option.id === "model");
    if (!model?.currentValue) {
      throw new ProbeError("model_missing", "ACP advertised no current model");
    }
    const selectedOptions = await probe.setConfig(
      sessionId,
      "model",
      model.currentValue,
    );
    const efforts = effortChoices(selectedOptions);
    if (efforts.length < 2) {
      throw new ProbeError(
        "effort_missing",
        "ACP advertised fewer than two reasoning efforts",
      );
    }
    const lowEffort = efforts[0];
    const highEffort = efforts.at(-1);
    await probe.setConfig(sessionId, "reasoning_effort", lowEffort);
    const low = await probe.prompt(
      sessionId,
      "Read README.md and reply with only its exact first heading. Do not modify files.",
    );
    await probe.setConfig(sessionId, "reasoning_effort", highEffort);
    const high = await probe.prompt(
      sessionId,
      "In this disposable workspace, create proof.txt containing exactly 'ACP workspace-write proof'. Commit README.md and proof.txt with commit message 'ACP workspace-write proof'. Do not access any path outside this workspace.",
    );
    const permissionPrompt = await probe.prompt(
      sessionId,
      "Attempt to create /tmp/forgelet-acp-permission-proof by running a shell command. If permission is requested, stop and report the denial. Do not work around a denial.",
    );
    const statusBeforeRestart = git(workspace, "status", "--porcelain");
    const commit = git(workspace, "rev-parse", "HEAD");
    const commitFiles = git(workspace, "show", "--pretty=", "--name-only", "HEAD");

    await probe.stop({ terminate: true });
    const restarted = await new AcpProbe({
      cwd: workspace,
      command,
      env,
      timeoutMs: Number(process.env.DSH_ACP_TIMEOUT_MS || 120000),
    }).start(acp);
    try {
      await restarted.initialize();
      const listed = await restarted.list();
      const resumed = await restarted.resume(sessionId);
      const replayed = (restarted.updates.get(sessionId) || []).length;
      const history = await restarted.prompt(
        sessionId,
        "Without using tools, state the exact first heading you read from README.md earlier. Answer with only that heading.",
      );
      await restarted.close(sessionId);
      evidence = {
        evidence_version: 1,
        mode: "live",
        timestamp: new Date().toISOString(),
        dsh_version: dshVersion,
        protocol_version: initialized.protocolVersion,
        capabilities: initialized.agentCapabilities,
        advertised_options: options,
        selected_model: model.currentValue,
        low_effort: lowEffort,
        high_effort: highEffort,
        low,
        high,
        permission_prompt: permissionPrompt,
        permission_decisions: probe.permissions,
        git: {
          status_clean: statusBeforeRestart === "",
          commit,
          changed_files: commitFiles ? commitFiles.split("\n") : [],
        },
        resume: {
          listed,
          config_options: resumed.configOptions,
          replayed_updates: replayed,
          history,
        },
        terminal_reason: "closed",
        limitations: [],
      };
      if (probe.permissions.length === 0) {
        evidence.limitations.push(
          "live model did not trigger a permission request in the bounded permission prompt",
        );
      }
      if (!evidence.git.status_clean || !commitFiles.split("\n").includes("proof.txt")) {
        evidence.limitations.push(
          "live model did not complete the requested workspace-write commit",
        );
      }
    } finally {
      await restarted.stop({ terminate: true });
    }
    writeEvidence(evidenceFile, evidence);
    return evidence;
  } catch (error) {
    const failure = {
      evidence_version: 1,
      mode: "live",
      timestamp: new Date().toISOString(),
      dsh_version: dshVersion,
      session_id: sessionId || null,
      error: {
        code: error.code || "live_failed",
        message: boundedText(error.message, 500),
        diagnostics: error.detail?.diagnostics || probe.diagnostics(),
      },
      terminal_reason: "failed",
    };
    writeEvidence(evidenceFile, failure);
    throw error;
  } finally {
    await probe.stop({ terminate: true });
    rmSync(workspace, { recursive: true, force: true });
    rmSync(dshHome, { recursive: true, force: true });
  }
}

function argument(args, name, fallback = null) {
  const index = args.indexOf(name);
  return index >= 0 && args[index + 1] ? args[index + 1] : fallback;
}

const invokedDirectly =
  process.argv[1] &&
  path.resolve(process.argv[1]) === path.resolve(new URL(import.meta.url).pathname);

if (invokedDirectly) {
  const args = process.argv.slice(2);
  const mode = args[0];
  try {
    if (mode === "self-test") {
      const root = path.resolve(argument(args, "--root", tmpdir()));
      const fakePeer = path.resolve(
        argument(
          args,
          "--fake-peer",
          path.join(path.dirname(new URL(import.meta.url).pathname),
                    "..", "..", "test", "swarmforge",
                    "fake_dsh_acp_peer.mjs"),
        ),
      );
      const evidence = path.resolve(
        argument(args, "--evidence", path.join(root, "acp-self-test.json")),
      );
      const result = await selfTest(fakePeer, root, evidence);
      process.stdout.write(JSON.stringify(result) + "\n");
    } else if (mode === "live") {
      const root = path.resolve(argument(args, "--root", process.cwd()));
      const evidence = path.resolve(
        argument(args, "--evidence", path.join(root, "tmp",
                                               "dsh-acp-live-evidence.json")),
      );
      const result = await liveTrial({
        root,
        evidenceFile: evidence,
        dshBin: argument(args, "--dsh", "dsh"),
      });
      process.stdout.write(JSON.stringify(result) + "\n");
    } else {
      throw new ProbeError(
        "usage",
        "Usage: dsh_acp_probe.mjs self-test --root DIR [--fake-peer FILE] [--evidence FILE] | live --root DIR [--evidence FILE] [--dsh PATH]",
      );
    }
  } catch (error) {
    process.stderr.write(
      JSON.stringify({
        error: {
          code: error.code || "probe_failed",
          message: boundedText(error.message, 500),
        },
      }) + "\n",
    );
    process.exit(1);
  }
}

export { AcpProbe, ProbeError };
