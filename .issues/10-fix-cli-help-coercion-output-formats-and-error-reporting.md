# Fix CLI help, coercion, output formats, and error reporting

## Context

The alpha review reproduced four CLI contract failures:

- `issues done 1 --help` marks issue 1 done instead of showing help.
- `issues add "Title" --json` creates the issue but selects human output;
  placing `--json` before `add` works.
- `issues set 1 blocked-by 2` crashes because the parser coerces the value to a
  number before string-based field coercion. Numeric tags fail similarly.
  `issues set 28 related '27'`, run in email-helper on 2026-10-06, crashed the
  same way with `java.lang.Long cannot be cast to java.lang.CharSequence`. The
  trace points at the help-flag check in `run` (`cli.clj:447`), which calls
  `str/starts-with?` on arguments that are already numbers, so the crash comes
  before `set` sees the value.
- Running an unknown command, or `show` outside a project, can exit 1 with both
  stdout and stderr empty. `-main` flushes stdout after printing the error to
  stderr, then exits.

These affect both interactive safety and agent/script integration. Most current
CLI tests call `run` in process and do not exercise stream flushing at exit.

## Acceptance criteria

- [ ] Recognized help flags before or after a subcommand show help and perform no
  mutation, open no editor/browser, and start no server.
- [ ] JSON and EDN output selection works consistently in supported global-flag
  positions, including after an add title, and matches the documented contract.
- [ ] Treat `set` values as strings until field-specific coercion. Cover a single
  numeric blocker, a single numeric related link, numeric title/tag text, comma-separated sets, and empty sets.
- [ ] Invalid arguments produce a useful diagnostic and nonzero exit instead of
  an incidental type exception.
- [ ] Error output is flushed to stderr before exit; success data remains on
  stdout, with predictable exit codes and documented structured-error behavior.
- [ ] Add actual CLI subprocess tests that capture stdout, stderr, and exit code,
  including failed commands, doctor exit 2, trailing format flags, and help on
  mutating commands. Assert issue files are unchanged after help.
- [ ] Update README and shipped skill guidance to match the repaired behavior,
  removing workarounds that are no longer necessary.

## Implementation notes

Inspect command dispatch and option capture in `src/issues/cli.clj`, especially
`cmd-add`, the `set` argument specification, `run`, and `-main`. Retain the useful
in-process result-map API while testing the process entry point separately.

## Log

- 2026-09-04: Filed from the alpha readiness review; implementation not started.
