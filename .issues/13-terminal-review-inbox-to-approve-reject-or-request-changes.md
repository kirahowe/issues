# Terminal review inbox to approve, reject, or request changes

## Context

Kira wants to work through the issues waiting on them without leaving the
terminal. `attention` lists them but offers no way to act, and closing or
returning an issue by hand means a `set` plus an edit to the details file.

## Acceptance criteria

- [x] `issues tui` lists every issue in review across the configured roots;
      `--project` narrows to one
- [x] Each issue opens in a scrollable detail view showing its details text
- [x] `a` approves (done), `c` requests changes (back to ready), `r` rejects
      (dropped)
- [x] A note is required for request changes and optional otherwise; it is
      logged as a dated line under the details `## Log`
- [x] `e` opens the details in `$EDITOR`; the terminal is restored on exit
- [x] Tests cover the log placement, the verdicts on disk, and the key and
      render state machine

## Plan

- `issues.review`: `append-log` and `apply-verdict!`
- `issues.tui`: pure `step` and `render`, then a raw-mode loop over `stty`
  and ANSI escapes, because babashka has no curses library
- `cli`: `tui` command; README and SKILL.md

## Log

- 2026-10-19 filed and implemented; driven end to end through a pty
