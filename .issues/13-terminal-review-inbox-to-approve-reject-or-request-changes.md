# Terminal review inbox to approve, reject, or request changes

## Context

Kira wants to work through the issues waiting on them without leaving the
terminal. `attention` lists them but offers no way to act, and closing or
returning an issue by hand means a `set` plus an edit to the details file.

## Acceptance criteria

- [x] `issues tui` lists every issue in review across the configured roots;
      `--project` narrows to one
  - Receipt: `cmd-tui` in src/issues/cli.clj scopes like `attention`;
    commit faf954a
  - Receipt: pty run against a scratch root listed demo#1-3 in priority order
- [x] Each issue opens in a scrollable detail view showing its details text
  - Receipt: `issues.tui-test/rendering` "detail" asserts the details text
    shows; `bb test issues.tui-test` passed, 6 tests, 0 failures
- [x] `a` approves (done), `c` requests changes (back to ready), `r` rejects
      (dropped)
  - Receipt: pty run left demo#2 done, demo#3 ready, demo#1 dropped
  - Receipt: `issues.review-test/verdicts`; `bb test issues.review-test`
    passed, 3 tests, 0 failures
- [x] A note is required for request changes and optional otherwise; it is
      logged as a dated line under the details `## Log`
  - Receipt: pty run refused an empty note, then wrote
    `- 2026-10-19 changes requested: more tests` to demo#3's Log
  - Receipt: `issues.tui-test/verdict-prompts` and
    `issues.review-test/append-log-places-the-line-in-the-log-section`
- [x] `e` opens the details in `$EDITOR`; the terminal is restored on exit
  - Receipt: pty run with a stub `$EDITOR` appended a line to demo#3's
    details and returned to the TUI
  - Receipt: `stty -g` before and after the run match except for PENDIN,
    which macOS sets on any return to canonical mode (plain `stty -icanon`
    then a restore leaves it the same way)
- [x] Tests cover the log placement, the verdicts on disk, and the key and
      render state machine
  - Receipt: test/issues/review_test.clj and test/issues/tui_test.clj;
    `bb test` passed, 146 tests, 0 failures

## Plan

- `issues.review`: `append-log` and `apply-verdict!`
- `issues.tui`: pure `step` and `render`, then a raw-mode loop over `stty`
  and ANSI escapes, because babashka has no curses library
- `cli`: `tui` command; README and SKILL.md

## Log

- 2026-10-19 filed and implemented; driven end to end through a pty
- 2026-10-19 receipts added; the `e` key and terminal restore were verified
  after the first hand-off
