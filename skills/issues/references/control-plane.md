# Discovery and less frequent operations

## Command scope

Global flags being accepted does not mean every command honors them alike.

| Commands | Actual scope |
| --- | --- |
| `list`, `inbox`, `next`, `status`, `doctor`, `ui` | Local by default; `--project` selects a discovered project; `--all` selects all and takes precedence |
| `show`, `details`, `edit`, `add`, `set`, `start`, `review`, `done`, `drop`, `block`, `unblock`, `index` | One project, from `--project` or nearest `.issues/` above `--dir`/cwd; `--all` does not fan out mutations |
| `init` | Exactly `--dir`/cwd; does not use `--project` to select a root |
| `attention` | All by default; `--project` narrows unless `--all` is also present; `--dir` alone does not narrow |
| `projects`, `snapshot`, `analyze` | Always crawl configured roots; `--project` and `--dir` do not narrow |
| `config` | Effective configuration and its source path |
| `insights` | Previously saved analysis file, not a fresh scan |

Avoid combining `--all` with `--project`. To target a particular checkout,
use only `--dir`, not `--project`.

## Discovery and new projects

Start with `issues config --json` and `issues projects --json` when a project
is missing or a project id resolves to an unexpected copy. Projects output
includes canonical `projects`, VCS roots without trackers as `candidates`,
duplicate-id copies as `shadowed`, and missing-root `warnings`.

Configuration is EDN. `--config /path/config.edn` overrides `ISSUES_CONFIG`,
which overrides the default XDG config location (normally
`~/.config/issues/config.edn`). A missing config uses defaults:

```clojure
{:roots ["~/code/projects"]
 :max-depth 2
 :skip-dirs #{"workspaces" "node_modules" "target" ".git" ".jj"}}
```

Merge changes into existing configuration when configuring discovery is in
scope. A direct `--dir` operation needs no discovery configuration.

The crawler starts each root at depth 0. It skips hidden children, symlinked
children, and configured directory names, and stops descending when it finds
`.issues/`. A tracker on a parent collection directory can therefore hide
nested projects. Projects with the same id are deduped by shortest absolute
path string, with lexical tie-breaking; this need not be the desired copy.

Use a stable, distinct id for each project. Without `.issues/project.edn`
containing `{:id "my-project"}`, the id falls back to the directory name.
`init --id` writes this file only when absent; it does not rename an existing
configured project. Renaming ids affects `related` references elsewhere.
Keep the same id across workspace copies of the same project and target the
active copy with `--dir`.

## Queries and diagnostics

```sh
issues list --status review --json
issues list --type bug -p p1 --tag cli --json
issues list --closed --json
issues list --status done --json
issues next --all --json
issues status --all --json
issues doctor --all --json
issues snapshot --json
issues snapshot --with-details --json
```

`list` combines filters, shows open issues by default, and includes closed
statuses with `--closed` or an explicit `--status`. There is no CLI text
search flag: filter structured output for title matching. `list` sorts by
lifecycle then priority; `next` sorts eligible ready issues by priority,
project id, then numeric issue id. A null `next.issue` means no eligible
ready work, not an empty backlog. Inspect inbox, blocked, or active work as
appropriate rather than promoting something arbitrarily.

Valid statuses: inbox, ready, in-progress, review, blocked, done, dropped.
Types: feature, bug, chore, idea. Priorities: p0 through p3, with p0 highest.

Local doctor catches malformed metadata, duplicate ids, dangling local
blockers, missing details, and index problems. Cross-project doctor also
reports dangling related references and shadowed projects. It does not
establish that dependency graphs are acyclic or implementation is correct.
Use project tests to verify implementation.

## Analysis and browser board

For possible duplicate requests:

```sh
issues analyze --kind duplicate-titles --threshold 0.5 --no-save --json
issues insights --kind duplicate-titles --json
```

The shipped analyzer compares title word overlap, including pairs within
the same project. It is a heuristic, not a semantic duplicate decision.
Read both issues before proposing consolidation. Closed issues are excluded
unless `--include-closed` is supplied. Prefer `--no-save` for an ad hoc read;
otherwise analysis overwrites the saved insights file (normally
`~/.local/share/issues/insights.edn`, honoring XDG data home). `--file` selects
an alternate saved file for `analyze` or `insights`. Saved insights may be
stale; these commands do not mutate issue metadata.

Run `issues ui` when the user wants the browser board. It is read-only,
binds to 127.0.0.1, and re-reads files on each request. Use a managed
background process because it blocks until stopped. `--all` shows all
projects, `--no-open` suppresses browser launch, and `--port 0` chooses a
free port (default is 4477). Report the printed URL and how to stop it.
