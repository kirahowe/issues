---
name: issues
description: Track work with the `issues` CLI (per-project `.issues/` directories plus a cross-project control plane). Use at session start to see what needs attention, whenever Kira mentions an issue, a feature request, a bug to file, or asks what to work on next, and when finishing work so the issue moves to review.
allowed-tools: Bash(issues *)
---

# issues

One global CLI, installed with bbin. Inside a project it acts on the nearest
`.issues/` directory above the cwd; `--all` spans every discovered project
and `--project <id>` picks one. Add `--edn` (or `--json`) whenever you will
read the output programmatically; the human table is for Kira.

## The loop

1. **Session start.** `issues attention --edn`. `:claude` holds inbox and
   ready issues (yours to flesh out or implement), `:kira` holds issues in
   review (theirs), `:blocked` is parked.
2. **Flesh out an inbox request.** Kira's title is the request, verbatim.
   - `issues show <id> --edn` for the metadata.
   - `issues details <id>` creates the markdown details file if needed and
     prints its path. Write Context, Acceptance criteria (checkboxes), and
     Plan into it directly; the tool never parses that file.
   - Set what you learned: `issues set <id> type bug`, `issues set <id>
     priority p1`, `issues set <id> tags a,b`, `issues block <id> --on <n>`.
   - Finish with `issues set <id> status ready`. That bumps `updated`;
     editing the markdown alone does not.
3. **Implement.** `issues next` picks the highest-priority unblocked ready
   issue. `issues start <id>` before you begin. Commit the `.issues/` changes
   together with the code they describe, in the same commits, following the
   repo's commit rules; never make a separate "update issue" commit.
4. **Hand back.** When implemented and verified, `issues review <id>`. Only
   Kira runs `issues done <id>`. Use `issues drop <id>` for work that will
   not happen, with the reason in the details file.
5. **File follow-ups you discover.** `issues add "<title>" --type chore -p p3`
   in the project they belong to. Keep the title as a one-line request.

## Rules

- Ids are plain integers, never zero-padded: `issues show 12`, file
  `12-slug.edn`. Cross-project refs are `project#12`.
- Never edit `.issues/README.md`; it is generated. Never hand-edit an
  issue's `:updated`; the CLI maintains it.
- `issues doctor` (or `--all`) before opening completion review; exit 2
  means an error such as a duplicate id or a dangling `:blocked-by`.
- Run `issues projects` when a project seems missing: it lists VCS roots
  without `.issues/` as candidates, and `issues init` inside one adopts it.
- Statuses: inbox, ready, in-progress, review, blocked, done, dropped.
  Types: feature, bug, chore, idea. Priorities: p0 (drop everything) to p3.

## Reference

```
issues add <title...> [--type t] [-p p] [--tags a,b]
issues list [--status s] [--type t] [-p p] [--tag t] [--closed] [--all]
issues show | details | edit | start | review | done | drop <id>
issues set <id> <status|type|priority|title|tags|blocked-by|related> <value>
issues block <id> --on <n>    issues unblock <id> [--on <n>]
issues next | inbox [--all]   issues index   issues doctor [--all]
issues projects | status [--all] | attention [--project id] | snapshot [--with-details] | config
issues analyze [--kind k] [--threshold x] [--include-closed] [--no-save] [--file p]
issues insights [--kind k] [--file p]
```
