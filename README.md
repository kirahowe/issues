# issues

A per-project issue tracker plus a control plane that aggregates every
project's issues. Each project keeps its own `.issues/` directory under
version control; one global `issues` CLI works inside any project and, given
directories to crawl, across all of them.

Built for a human and an agent sharing the same list: the human files brief
requests, the agent fleshes them out, implements them, and hands them back
for review.

## Install

Needs [babashka](https://babashka.org) and [bbin](https://github.com/babashka/bbin).

```sh
bb install          # runs `bbin install .`, publishing `issues` on your PATH
```

## Quick start

```sh
cd ~/code/projects/myproject
issues init                          # creates .issues/ (id defaults to the dir name)
issues add "Support age as a backend" # files an inbox request, one EDN file
issues list                          # open issues, sorted by lifecycle then priority
issues next                          # highest-priority unblocked ready issue
issues start 1 && issues review 1    # move it along; `done`, `drop`, `block`, `unblock` too
issues show 1                        # metadata plus the details file, if any
issues ui                            # browser board of every project at or below here
```

Across projects (crawls `~/code/projects` two levels deep unless configured):

```sh
issues projects          # discovered projects, plus VCS roots that could become one
issues attention         # what needs Kira (review) and Claude (inbox, ready)
issues tui               # review inbox: approve, request changes, or reject
issues approve 12 --note "ship it"   # the same verdicts, one at a time: approve, changes, reject
issues list --all        # every open issue everywhere; --project <id> narrows
issues doctor --all      # every problem; exit 2 when any is an error
issues snapshot --json   # the whole cross-project data value
```

Every command takes `--json` or `--edn`. `issues ui` serves a read-only
board on 127.0.0.1 (columns per status, one page per issue with its details
rendered) and re-reads `.issues/` on every request, so CLI edits show up on
refresh. By default it shows every project at or below the current directory
(starting from the enclosing project's root when run inside one), nested
projects included, crawling as deep as the configured `:max-depth`.
`--local` narrows to the one project at or above the directory, `--project
<id>` picks a discovered project, and `--all` spans every configured root.

`issues tui` opens a terminal inbox of everything in `review` across the
configured roots, or one project's with `--project <id>`, with each issue's
receipted criteria count (`3/4!` when something is unchecked or has no
receipt). Open an issue to read its details, then press `a` to approve it (`done`), `c` to request
changes (back to `ready`, where `next` and the agent pick it up again) or
`r` to reject it (`dropped`). Each verdict asks for a note: optional for
approve and reject, required for changes. A note goes into the details
file as a dated line under `## Log`, such as `- 2026-10-19 changes
requested: cover the empty case`. `e` opens the details in `$EDITOR` when
the note needs more than one line. On quitting, it prints the verdicts you
gave.

`issues approve`, `issues changes` and `issues reject` give the same
verdicts from the command line, for a program such as overview that has
no terminal to hand over. Each takes the issue's id and `--note <text>`,
which `changes` requires. Unlike the inbox, they accept an issue in any
status.

## How it works

**Source of truth is the project.** `.issues/12-add-age-backend.edn` holds the
metadata; an optional `12-add-age-backend.md` next to it holds the prose
(context, acceptance criteria, plan). Ids are per-project integers, never
zero-padded; the number in the file name is authoritative and the slug is
cosmetic. Cross-project references look like `secrets#12`.

```clojure
{:id 12
 :title "Add age encryption backend"
 :status :inbox          ; inbox | ready | in-progress | review | blocked | done | dropped
 :type :feature          ; feature | bug | chore | idea
 :priority :p2           ; p0 .. p3
 :created "2026-09-01"
 :updated "2026-09-01"
 :tags #{"crypto"}
 :blocked-by #{}         ; ids in this project
 :related #{}            ; "project#id" strings
 :details "12-add-age-backend.md"}
```

The lifecycle is `inbox` (raw request) → `ready` (fleshed out) →
`in-progress` → `review` (implemented, awaiting the human) → `done`, with
`blocked` and `dropped` on the side. An issue reaches `review` only once every
item under its details' `## Acceptance criteria` is checked off with at
least one nested `- Receipt:` line, the evidence for the claim (a command
and its result, a commit, a test). `issues review` lists what's missing,
and `--force` skips the check. Issue edits ride along with the code in
the same commits, so an issue's history is the repo's history.

`.issues/README.md` is generated (first line is a marker) so GitHub renders an
index; the tool never overwrites a README it did not write.

**The control plane is derived, never authoritative.** It crawls the
configured roots for directories containing `.issues/`, stops descending once
it finds one (so jj workspace copies under `<project>/workspaces/` never
double-count), and dedupes by project id with the shortest path winning.
Nothing is cached in v0; a few dozen projects parse in well under a second.

Config lives at `~/.config/issues/config.edn` (or `$ISSUES_CONFIG`):

```clojure
{:roots ["~/code/projects" "~/code/seeq"]
 :max-depth 2
 :skip-dirs #{"workspaces" "node_modules" "target" ".git" ".jj"}}
```

**The intelligence layer is a seam, not a feature yet.** `issues analyze`
runs every analyzer in `issues.analyze` over the snapshot and saves the
insights under `~/.local/share/issues/`; `issues insights` shows them. The
one shipped analyzer flags near-duplicate titles across projects. An
embedding- or LLM-backed analyzer is a new `analyze` method with the same
input and output shape.

## Development

```sh
bb test             # bb-native clojure.test; `bb test issues.store-test` for one ns
bb lint             # clj-kondo (needs a JDK)
bb fmt              # cljfmt (needs a JDK)
bb ci               # fmt:check, lint, test
bb issues <args>    # run the CLI from source
```

Namespaces, bottom up: `issue` (pure parse/render/validate), `store` (one
`.issues/` dir), `project` (locate, init, read), `config`, `discover`
(crawl), `snapshot` (everything as one value), `query`, `render`, `analyze`,
`markdown` and `ui` (the local board), `review` and `tui` (the review
inbox), `cli`. This repo tracks its own
follow-ups in `.issues/`.
