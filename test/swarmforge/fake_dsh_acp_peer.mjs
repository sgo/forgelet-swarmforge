#!/usr/bin/env node
import { createRequire } from "node:module";
import { readFileSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import path from "node:path";
import { Readable, Writable } from "node:stream";
import { pathToFileURL } from "node:url";
import { spawnSync } from "node:child_process";

const mode = process.env.FAKE_ACP_MODE || "normal";
const stateFile = process.env.FAKE_ACP_STATE;

if (mode === "malformed") {
  process.stdout.write("{not-json}\\n");
  await new Promise((resolve) => setTimeout(resolve, 100));
  process.exit(0);
}
if (mode === "early-exit") {
  await new Promise((resolve) => setTimeout(resolve, 30));
  process.exit(23);
}

const installRoot =
  path.join(
    process.env.DSH_INSTALL_ROOT ||
      path.join(homedir(), ".local", "share", "forgelet-dsh"),
    "current",
    "node_modules",
  );
const requireFromInstall = createRequire(path.join(installRoot, "package.json"));
const acp = await import(
  pathToFileURL(requireFromInstall.resolve("@agentclientprotocol/sdk"))
);

const sessions = new Map();
const active = new Set();
const promptsInFlight = new Set();
const cancelled = new Set();

function loadState() {
  if (!stateFile) return;
  try {
    const stored = JSON.parse(readFileSync(stateFile, "utf8"));
    for (const session of stored.sessions || []) {
      sessions.set(session.id, session);
    }
  } catch {
    // A missing state file is a fresh disposable peer.
  }
}

function saveState() {
  if (!stateFile) return;
  writeFileSync(
    stateFile,
    JSON.stringify({ sessions: [...sessions.values()] }, null, 2) + "\n",
  );
}

function configOptions(session) {
  return [
    {
      id: "model",
      name: "Model",
      category: "model",
      type: "select",
      currentValue: session.model,
      options: [
        { value: "fake/read-only", name: "Fake Read Only" },
        { value: "fake/implementation", name: "Fake Implementation" },
      ],
    },
    {
      id: "reasoning_effort",
      name: "Reasoning effort",
      category: "thought_level",
      type: "select",
      currentValue: session.effort,
      options: [
        { value: "", name: "Provider default" },
        { value: "low", name: "Low" },
        { value: "medium", name: "Medium" },
        { value: "high", name: "High" },
      ],
    },
  ];
}

function newSession(cwd) {
  const id = `fake-session-${sessions.size + 1}`;
  const session = {
    id,
    cwd,
    model: "fake/read-only",
    effort: "low",
    active: false,
    messages: [],
  };
  sessions.set(id, session);
  saveState();
  return session;
}

function update(ctx, session, value) {
  return ctx.client.notify(acp.methods.client.session.update, {
    sessionId: session.id,
    update: value,
  });
}

function git(args) {
  const result = spawnSync("git", args, {
    cwd: process.cwd(),
    encoding: "utf8",
    env: process.env,
  });
  if (result.status !== 0) {
    throw new Error(result.stderr || `git ${args.join(" ")} failed`);
  }
  return result.stdout.trim();
}

function permission(ctx, session, text) {
  const toolCallId = `permission-${session.messages.length + 1}`;
  return ctx.client
    .request(acp.methods.client.session.requestPermission, {
      sessionId: session.id,
      toolCall: { toolCallId },
      options: [
        {
          optionId: "allow-once",
          name: "Allow once",
          kind: "allow_once",
        },
        {
          optionId: "reject-once",
          name: "Reject",
          kind: "reject_once",
        },
      ],
    })
    .then(({ outcome }) => {
      const decision =
        outcome.outcome === "cancelled"
          ? "cancelled"
          : outcome.optionId === "allow-once"
            ? "allowed"
            : "rejected";
      session.messages.push(`${text}:${decision}`);
      saveState();
      return decision;
    });
}

async function prompt(ctx, params) {
  const session = sessions.get(params.sessionId);
  if (!session) throw new Error("unknown fake session");
  if (promptsInFlight.has(session.id)) {
    throw new Error("session already has a prompt");
  }
  promptsInFlight.add(session.id);
  cancelled.delete(session.id);

  const text = params.prompt
    .filter((item) => item.type === "text")
    .map((item) => item.text)
    .join("");
  const pinned = {
    model: session.model,
    effort: session.effort,
  };
  session.messages.push(text);
  saveState();

  await update(ctx, session, {
    sessionUpdate: "agent_thought_chunk",
    content: { type: "text", text: "Considering the bounded fake prompt." },
  });
  await update(ctx, session, {
    sessionUpdate: "tool_call",
    toolCallId: `tool-${session.messages.length}`,
    title: "Run fake bounded tool",
    kind: "execute",
    status: "pending",
  });

  if (text.includes("git-commit")) {
    writeFileSync(path.join(process.cwd(), "proof.txt"),
                  "ACP workspace-write proof\n");
    git(["add", "README.md", "proof.txt"]);
    git(["commit", "-q", "-m", "ACP workspace-write proof"]);
  }

  if (text.includes("permission-")) {
    await permission(ctx, session, text);
  }

  const delay = text.includes("slow") ? 180 : 20;
  await new Promise((resolve) => setTimeout(resolve, delay));
  await update(ctx, session, {
    sessionUpdate: "tool_call_update",
    toolCallId: `tool-${session.messages.length}`,
    status: "completed",
  });
  await update(ctx, session, {
    sessionUpdate: "agent_message_chunk",
    content: {
      type: "text",
      text: `handled:${pinned.effort}:${session.effort}`,
    },
  });
  await update(ctx, session, {
    sessionUpdate: "usage_update",
    used: 42,
    size: 1000,
  });

  if (mode === "transport-loss") {
    process.stdout.destroy();
    process.exit(24);
  }
  if (mode === "exit-at-prompt") {
    process.exit(25);
  }

  if (text.includes("cancel-")) {
    const deadline = Date.now() + 5_000;
    while (!cancelled.has(session.id) && Date.now() < deadline) {
      await new Promise((resolve) => setTimeout(resolve, 20));
    }
    promptsInFlight.delete(session.id);
    return { stopReason: "cancelled" };
  }

  promptsInFlight.delete(session.id);
  saveState();
  return { stopReason: "end_turn" };
}

loadState();

const app = acp
  .agent({ name: "fake-dsh-acp-peer" })
  .onRequest(acp.methods.agent.initialize, async () => {
    if (mode === "refuse-initialize") {
      throw new Error("fake handshake refused");
    }
    return {
      protocolVersion: acp.PROTOCOL_VERSION,
      agentInfo: { name: "fake-dsh-acp-peer", version: "1.0.0" },
      agentCapabilities: {
        promptCapabilities: {
          image: false,
          audio: false,
          embeddedContext: false,
        },
        sessionCapabilities: {
          list: {},
          resume: {},
          close: {},
        },
      },
      authMethods: [],
    };
  })
  .onRequest(acp.methods.agent.session.new, async ({ params }) => {
    const session = newSession(params.cwd);
    active.add(session.id);
    return {
      sessionId: session.id,
      configOptions: configOptions(session),
    };
  })
  .onRequest(acp.methods.agent.session.list, async ({ params }) => {
    const listed = [...sessions.values()]
      .filter((session) => !active.has(session.id))
      .filter((session) => !params.cwd || session.cwd === params.cwd)
      .map((session) => ({
        sessionId: session.id,
        cwd: session.cwd,
      }));
    return { sessions: listed };
  })
  .onRequest(acp.methods.agent.session.resume, async ({ params }) => {
    const session = sessions.get(params.sessionId);
    if (!session || session.cwd !== params.cwd) {
      throw new Error("fake session cannot be resumed");
    }
    active.add(session.id);
    return { configOptions: configOptions(session) };
  })
  .onRequest(acp.methods.agent.session.close, async ({ params }) => {
    active.delete(params.sessionId);
    saveState();
    return {};
  })
  .onRequest(
    acp.methods.agent.session.setConfigOption,
    async ({ params }) => {
      const session = sessions.get(params.sessionId);
      if (!session) throw new Error("unknown fake session");
      if (params.configId === "model") session.model = params.value;
      else if (params.configId === "reasoning_effort") {
        session.effort = params.value;
      } else throw new Error("unknown fake config option");
      saveState();
      return { configOptions: configOptions(session) };
    },
  )
  .onRequest(acp.methods.agent.session.prompt, async (ctx) => {
    try {
      return await prompt(ctx, ctx.params);
    } catch (error) {
      process.stderr.write(`fake prompt failed: ${error.stack || error}\n`);
      throw error;
    }
  })
  .onNotification(acp.methods.agent.session.cancel, ({ params }) => {
    cancelled.add(params.sessionId);
  });

app.connect(
  acp.ndJsonStream(
    Writable.toWeb(process.stdout),
    Readable.toWeb(process.stdin),
  ),
);

await new Promise((resolve) => {
  process.stdin.once("end", resolve);
  process.stdin.once("close", resolve);
  process.stdin.resume();
});
