# DeepSeek Harness ACP runner proof

This layer ships a trial-only ACP probe at
`swarmforge/scripts/dsh_acp_probe.mjs`. It does not replace Codex or change any
project runner.

## Tested release

The live trial used the installed pinned DeepSeek Harness release
`0.2.2-rc.1` through its shipped `dsh --profile acp` profile. It used a fresh
temporary `DSH_HOME`, a minimal environment containing only `DEEPSEEK_API_KEY`,
and a disposable Git repository with `.git` inside the workspace. No
repository or host credentials were supplied.

The raw live evidence is stored outside Git at
`tmp/dsh-acp-live-evidence.json`, mode `600`. The probe redacts exact secret
values, credential-shaped strings and host paths before writing evidence.

## Session lifecycle

The live trial initialized ACP v1 and found the advertised model and effort
options. It selected the advertised current model and used the first and last
non-empty advertised reasoning efforts. Those were `off` and `max`; no effort
identifier is hard-coded.

The ACP surface supports:

- `session/new` for persistent independent sessions;
- `session/list` for resumable inactive sessions;
- `session/resume` with the same workspace;
- `session/close` with quiescent persistence and teardown;
- `session/set_config_option` for model and effort;
- `session/prompt` with one prompt in flight per session;
- `session/cancel`;
- ordered `session/update` notifications;
- `session/request_permission`.

The probe permits concurrent prompts on independent sessions and rejects a
second prompt on a session with one already in flight.

## Event mapping

ACP semantic updates map to this small runner model:

| ACP update | Forgelet event |
|---|---|
| `agent_thought_chunk` | `thought` |
| `agent_message_chunk` | `assistant_text` |
| `tool_call` | `tool_start` |
| `tool_call_update` | `tool_update` |
| `usage_update` | `context_usage` |
| `config_option_update` | `config_option_update` |

The fixture proves ordered thought, tool-start, tool-update, assistant text and
context usage delivery. Raw provider deltas, presentation data and terminal
transcripts remain off the ACP wire.

## Effort and prompt pinning

Each prompt result records the option state at admission. The live low-effort
read took 2.3 seconds and ended with `end_turn`; the implementation-shaped
maximum-effort turn took 10.5 seconds and also ended with `end_turn`. Context
usage rose from 5,792 to 7,684 tokens across those turns.

The fixture changes effort while a prompt is in flight and proves that the
admitted turn keeps its original selection. ACP does not label the pinned route
in every update, so the probe exposes the admission snapshot rather than
inventing a route field.

## Permission boundary

When a client permission callback is absent, the probe rejects by default. An
explicit callback may select only the advertised one-shot allow or reject
option, or return cancellation. It never requests or silently chooses
unrestricted host access.

The fixture proves allow-once, reject-once, cancellation, malformed traffic,
handshake refusal, process exit and transport loss as bounded outcomes. In the
live trial, DSH's `workspace-write` policy allowed `/tmp` without asking, so no
live permission request occurred. That remains an observed limitation; the
protocol permission path itself is covered by the fake peer.

## Cancellation, restart and resume

The fixture proves a prompt can be cancelled while active and that independent
session history remains usable. Process termination after idle followed by a
new process using the same `DSH_HOME` can list and resume the session.
Resumption emitted zero replay updates; a follow-up prompt retained the prior
context and recovered the earlier `# ACP Proof` heading.

## Git workspace result

The live implementation-shaped turn created `proof.txt` and made a real commit
inside the disposable repository. `git status` was clean afterward and the
commit contained `proof.txt`. This used the shipped workspace-write policy; the
probe did not request unrestricted host access.

## Conclusion and limitations

ACP is a credible next resident-runner boundary. It provides persistent
sessions, semantic updates, model/effort controls, permission callbacks,
cancellation, restart/resume and bounded tool execution without a pseudo-
terminal.

Known preview limitations:

- permission requests depend on the mounted policy and operation; a
  workspace-write operation can be allowed without prompting;
- ACP does not expose transcript replay or a per-update pinned-route label;
- additional workspace directories are unsupported;
- client filesystem and terminal capabilities are intentionally unused because
  DSH owns its tools;
- the protocol and DSH package remain developer-preview surfaces.

## Live probe

The live request is explicitly opt-in:

```sh
DSH_ACP_LIVE=1 node swarmforge/scripts/dsh_acp_probe.mjs live \
  --root "$PWD" --evidence ./tmp/dsh-acp-live-evidence.json
```

Normal tests use only `test/swarmforge/fake_dsh_acp_peer.mjs`. They do not make
model requests.

## Rollback

No project runner has been changed. Rollback is to stop using and remove the
trial probe, fake peer and this decision note. No project state, stages,
sessions or runner configuration were migrated, so there is nothing to reverse
in active forges.
