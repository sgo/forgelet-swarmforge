import assert from "node:assert/strict";
import { mkdtempSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";
import {
  effortChoices,
  normalizeUpdate,
  redactSecrets,
  selfTest,
} from "../../swarmforge/scripts/dsh_acp_probe.mjs";

const repoRoot = process.cwd();
const fakePeer = path.join(
  repoRoot,
  "test",
  "swarmforge",
  "fake_dsh_acp_peer.mjs",
);

test("normalizes committed ACP updates into the runner event model", () => {
  assert.deepEqual(
    normalizeUpdate({
      sessionUpdate: "agent_thought_chunk",
      content: { type: "text", text: "thinking" },
    }),
    { type: "thought", text: "thinking" },
  );
  assert.deepEqual(
    normalizeUpdate({
      sessionUpdate: "agent_message_chunk",
      content: { type: "text", text: "answer" },
    }),
    { type: "assistant_text", text: "answer" },
  );
  assert.equal(
    normalizeUpdate({
      sessionUpdate: "tool_call",
      toolCallId: "tool-1",
      title: "Run tests",
      kind: "execute",
      status: "pending",
    }).type,
    "tool_start",
  );
  assert.equal(
    normalizeUpdate({
      sessionUpdate: "tool_call_update",
      toolCallId: "tool-1",
      status: "completed",
    }).type,
    "tool_update",
  );
  assert.deepEqual(
    normalizeUpdate({
      sessionUpdate: "usage_update",
      used: 41,
      size: 1000,
    }),
    { type: "context_usage", used: 41, size: 1000, cost: null },
  );
});

test("effort selection comes from advertised values", () => {
  const configs = [
    {
      id: "reasoning_effort",
      options: [
        { value: "", name: "Provider default" },
        { value: "low", name: "Low" },
        { value: "high", name: "High" },
      ],
    },
  ];
  assert.deepEqual(effortChoices(configs), ["low", "high"]);
});

test("redaction removes exact secrets and credential-shaped values", () => {
  const secret = "deepseek-secret-sentinel";
  const text = redactSecrets(
    `token=${secret} bearer=Bearer abcdefghijklmnop /Users/operator/private`,
    [secret],
  );
  assert.equal(text.includes(secret), false);
  assert.equal(text.includes("abcdefghijklmnop"), false);
  assert.equal(text.includes("/Users/operator/private"), false);
});

test("fake ACP peer proves the complete runner boundary", async () => {
  const root = mkdtempSync(path.join(tmpdir(), "forgelet-dsh-acp-test."));
  const evidenceFile = path.join(root, "evidence.json");
  const secret = "dsh-secret-never-print";
  process.env.DEEPSEEK_API_KEY = secret;
  const evidence = await selfTest(fakePeer, root, evidenceFile);

  assert.deepEqual(evidence.sessions, ["fake-session-1", "fake-session-2"]);
  assert.deepEqual(evidence.efforts, ["low", "medium", "high"]);
  assert.equal(
    evidence.initialized.capabilities.sessionCapabilities.list !== undefined,
    true,
  );
  assert.equal(
    evidence.initialized.capabilities.sessionCapabilities.resume !== undefined,
    true,
  );
  assert.equal(
    evidence.initialized.capabilities.sessionCapabilities.close !== undefined,
    true,
  );

  for (const result of [evidence.low, evidence.high, evidence.pin]) {
    const types = result.events.map((event) => event.type);
    assert.ok(types.indexOf("thought") < types.indexOf("tool_start"));
    assert.ok(types.indexOf("tool_start") < types.indexOf("tool_update"));
    assert.ok(types.indexOf("tool_update") < types.indexOf("assistant_text"));
    assert.ok(types.indexOf("assistant_text") < types.indexOf("context_usage"));
  }
  const lowEffort = evidence.low.selection_at_admission.find(
    (option) => option.id === "reasoning_effort",
  );
  const highEffort = evidence.high.selection_at_admission.find(
    (option) => option.id === "reasoning_effort",
  );
  assert.equal(lowEffort.currentValue, "low");
  assert.equal(highEffort.currentValue, "high");
  assert.ok(
    evidence.pin.events.some(
      (event) =>
        event.type === "assistant_text" && event.text === "handled:low:high",
    ),
    JSON.stringify(evidence.pin.events),
  );

  assert.deepEqual(
    evidence.permissions.map((result) => result.decision),
    ["allow", "reject", "cancel"],
  );
  assert.equal(
    evidence.permissions[0].permissions[0].option_id,
    "allow-once",
  );
  assert.equal(
    evidence.permissions[1].permissions[0].option_id,
    "reject-once",
  );
  assert.equal(
    evidence.permissions[2].permissions[0].outcome,
    "cancelled",
  );
  assert.equal(
    evidence.default_permission.permissions[0].decision,
    "reject",
  );
  assert.equal(evidence.cancellation.stop_reason, "cancelled");
  assert.equal(evidence.resumed.replayed_updates, 0);
  assert.equal(evidence.resumed.history.stop_reason, "end_turn");
  assert.equal(evidence.git.status_clean, true);
  assert.ok(evidence.git.changed_files.includes("proof.txt"));
  assert.ok(
    evidence.concurrent[0].session_id !==
      evidence.concurrent[1].session_id,
  );

  for (const failure of Object.values(evidence.failure_modes)) {
    assert.equal(failure.failure_observed, true);
    assert.match(failure.code, /(handshake|prompt)_failed/);
  }
  const serialized = JSON.stringify(evidence);
  const persisted = readFileSync(evidenceFile, "utf8");
  assert.equal(serialized.includes(secret), false);
  assert.equal(persisted.includes(secret), false);
});
