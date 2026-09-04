# Make doctor reliable on broken issue data

## Context

The alpha review confirmed that `doctor` can crash while trying to diagnose an
invalid record. With a generated README present, a ready issue missing `:type`
causes `project/index-problem` to render that invalid issue through
`store/table-row`; `(name (:type issue))` throws instead of reporting the
already-detected missing-field problem.

The diagnostic command must remain usable when metadata is malformed or a
workspace merge introduces duplicate ids. Existing issues#2 proposes a renumber
command; this issue covers reliable diagnosis and documented recovery without
requiring that command to be implemented first.

## Acceptance criteria

- [ ] `doctor` reports malformed EDN, missing required fields, invalid field
  types, and duplicate ids without crashing during validation or index checks.
- [ ] Continue examining other records and, with `--all`, other projects when an
  individual record is invalid; retain useful file/project context in findings.
- [ ] Return exit 2 when errors exist and exit 0 for warning-only results.
- [ ] Human, JSON, and EDN diagnostics remain usable for malformed records;
  generated-index comparison must not conceal their underlying problems.
- [ ] Diagnosis does not mutate source files.
- [ ] Document safe duplicate-id recovery, including updating dependencies and
  references, and reference the planned renumber workflow in issues#2.
- [ ] Add regression cases with an existing generated README, including a ready
  issue missing `:type`, malformed collection fields, unreadable EDN, and
  duplicate ids. Verify healthy records remain visible alongside failures.

## Implementation notes

Inspect `src/issues/project.clj` (`read-project`, `index-problem`),
`src/issues/store.clj` (`dir-problems`, index rendering), and the doctor command
in `src/issues/cli.clj`. Keep error diagnosis separate from assumptions made by
normal display rendering. Coordinate process-level output checks with the CLI
contract issue.

## Log

- 2026-09-04: Filed from the alpha readiness review; implementation not started.
