---
name: issues
description: Use the local issues CLI to file, triage, prioritize, and track work in projects with .issues/ directories, or survey issues across personal projects. Use when the user mentions this tracker, references a local project#id, asks what to work on next, or starts or finishes work tied to a local issue. Does not replace GitHub, Linear, or another explicitly chosen tracker.
---

# issues

Use the project's `.issues/` files as the source of truth. The global CLI
provides local workflows and a derived view across projects; it does not
sync with a remote issue service. Follow the project's own work and VCS
conventions. Reading the backlog does not authorize implementing it.

## Locate the tool and scope

- Use `issues` on PATH. If unavailable, a known checkout of this tool can run
  `bb --config /path/to/issues/bb.edn issues --dir /path/to/target ...`.
  Babashka 1.12.0+ is required. Do not assume the current project has a
  `bb issues` task. If neither route is available, report the missing CLI;
  install it only when setup is part of the request.
- For project work, use the working directory or explicit
  `--dir /path/to/project` (`-C`). Local resolution walks upward to the nearest
  `.issues/`; check that it belongs to the intended project.
- `--project <id>` resolves through discovery and overrides `--dir`. In a
  worktree or jj workspace, prefer `--dir` so changes land in that copy.
  Use `issues projects --json` to resolve an unfamiliar project id or path.
- Issue commands take a numeric id. Translate `secrets#12` into
  `issues --project secrets show 12 --json`; do not pass the full reference
  as the positional id. Use `project#id` in cross-project reports.
- If adopting a new project is requested, run
  `issues --dir /path/to/project init --id stable-project-id` at its root.
  This creates `.issues/` and pins its id when `project.edn` is absent.
  Reading a project or using this skill alone is not a request to adopt it.

## Read only what the task needs

Prefer `--json` or `--edn` for structured reads. Output is the command's
data itself, without an exit/kind/data envelope. `list` and `inbox` return
an `issues` array; `next` returns an `issue` object or null; `show` returns
the issue with `details-text`. Check exit status and stderr as well as stdout.
For `add`, put `--json`/`--edn` before the command: flags after the title
are reparsed for creation but do not select the output format in this version.

| Need | Command |
| --- | --- |
| Work already underway or waiting locally | `issues list --json` |
| Raw requests to flesh out | `issues inbox --json` |
| Next actionable ready item | `issues next --json` |
| One issue including its prose | `issues show 12 --json` |
| Cross-project overview of attention | `issues attention --json` |
| Attention for a discovered project | `issues attention --project secrets --json` |
| Open work across projects | `issues list --all --json` |

For a named task, inspect its issue directly. For an open-ended local work
session, inspect the local list before selecting work. Use global queries
when the user asks across projects. Fetch details only for relevant issues;
reserve `snapshot --with-details` for a task that needs the full corpus.

`attention` is global by default even inside a project; `--dir` does not
make it local. Its literal keys are `kira` (human review), `claude` (agent
triage: inbox and ready), and `blocked`. These are roles even when the agent
is Codex. It omits in-progress work, and its ready bucket is not filtered
for dependencies. `next` is the actionable-work selector.

## File, clarify, implement, hand back

1. **File or reuse.** Check relevant existing issues before adding a duplicate.
   Use `issues --json add 'Support age as a backend' --type feature -p p2`.
   New issues default to inbox, feature, p2. Keep the user's intent in the
   title and use the returned id; do not predict ids from a cached list.
   Record follow-ups when they are part of the requested tracking workflow;
   do not expand the implementation scope to tackle them automatically.
2. **Clarify.** Read `show`, then use `issues details 12` to create/link a
   details file and obtain its path. Edit that Markdown directly, preserving
   existing context. Use Context, Acceptance criteria, Plan, and Log as
   useful; record concrete success conditions and unresolved decisions.
   Prefer this to `issues edit`, which launches an interactive editor.
   Set justified metadata with `issues set 12 type bug`,
   `issues set 12 priority p1`, or `issues set 12 tags 'cli,ux'`.
   Move to `ready` with `issues set 12 status ready` when sufficiently clear.
3. **Implement authorized work.** Inspect the selected issue and its blockers,
   then `issues start 12`. `next` only recommends; it does not claim work.
   Status commands do not enforce prerequisites or ownership. Match the
   actual state, and do not reset an already active issue unnecessarily.
4. **Verify and hand back.** Update the details with results, relevant checks,
   and remaining limitations, then run `issues doctor --json` for the target
   project. Resolve problems introduced by the work. Use `issues review 12`
   once implemented and verified. Keep issue changes with the code they
   describe when committing, following local VCS rules. Human acceptance is
   the normal boundary for `done`; use `issues done 12` when the user accepts
   or explicitly requests closure. Use `drop` for intentionally abandoned
   work within the request, with a reason in the details.

Metadata changes through `set` and lifecycle commands bump `updated` and
regenerate the index. Creating/editing details alone does not bump `updated`.
Do not invent a metadata change solely to refresh the date.

## Dependencies and data integrity

- `issues block 12 --on 7` adds a same-project dependency and sets status to
  `blocked`. It verifies the target exists and rejects self-blocking.
- `issues unblock 12 --on 7` removes that edge. Bare `unblock 12` clears
  **all** blockers. A blocked issue becomes ready only when the remaining
  dependency set is empty; inspect remaining dependencies before clearing.
- Closing a blocker does not change the dependent's status. `next` considers
  only ready issues and rejects those with open blockers. Done/dropped
  blockers do not block; missing blockers also do not block, but are doctor
  errors. Reconcile explicit blocked status when work becomes actionable.
- `set` on `tags`, `blocked-by`, or `related` **replaces** the whole set from
  comma-separated input; `''` clears it. Read and merge existing values when
  adding entries. Prefer `block`/`unblock` for dependency changes:
  `set blocked-by` does not update status or verify referenced issues exist.
  A single numeric `set` value can also be auto-coerced and fail in this
  version; use `block --on` to add a single dependency.
- Cross-project links use `issues set 12 related 'other-project#3'`. They
  are informational, one-way links, not cross-project blockers. If work
  depends on another project, record that reason in details and explicitly
  set status to blocked until resolved.
- Use the CLI for metadata. The filename's numeric id is authoritative;
  slugs are cosmetic. Do not hand-edit `updated` or the generated
  `.issues/README.md`. `issues index` refreshes the index and refuses to
  overwrite a handwritten README; preserve that file.
- Doctor exits 2 for errors, 0 for no errors (warnings can remain). Exit 1
  generally means a command/config/input failure. Diagnostics use stderr,
  but a nonzero process can exit without flushing it. If doctor output is
  empty, inspect `show`/`list` issue `problems`, or `snapshot` project
  `problems`. Doctor diagnoses rather than repairs; avoid unrelated cleanup.

For discovery/configuration, advanced queries, analysis, and the browser
board, read [references/control-plane.md](references/control-plane.md).
