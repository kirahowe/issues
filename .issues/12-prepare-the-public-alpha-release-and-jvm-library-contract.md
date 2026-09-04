# Prepare the public alpha release and JVM library contract

## Context

The current feature set is sufficient for an alpha, but the public release
contract is incomplete. The repository lacks a license and hosted CI, and the
README installation instructions assume an existing checkout. `deps.edn`
advertises JVM library usage without documenting a supported API or examples.

The review ran `bb ci` successfully: 128 tests, 462 assertions, no failures or
errors. Loading all tests on the JVM failed because the UI tests require
`babashka.http-client`, which is not declared as a JVM test dependency. Excluding
UI, the JVM suite passed 125 tests and 435 assertions. These are review-time
results, not evidence that an eventual release revision passes.

The public attention result uses personal keys `:kira` and `:claude`. Generalize
these roles before external consumers build integrations around them.

## Acceptance criteria

- [ ] Record the maintainer's license choice and add the corresponding license
  file and appropriate project metadata.
- [ ] Define the alpha version and release notes, including known limitations,
  compatibility expectations, and upgrade/data-format guidance.
- [ ] Document reproducible installation from a clean environment and a pinned
  release, with supported Babashka/JDK versions and prerequisites; smoke-test
  installation and the basic init/add/list/show workflow outside this checkout.
- [ ] Add hosted CI running formatting, lint, and the complete Babashka suite.
- [ ] Provide a repeatable JVM test entry point and declare all required test
  dependencies so the complete suite, including UI HTTP tests, runs in CI.
- [ ] Document how to consume the JVM library, which namespaces/functions are
  supported, their principal data/error contracts, and a runnable usage example.
- [ ] Replace personal attention keys and labels with general human/agent roles
  consistently across queries, renderers, tests, README, and shipped skill docs.
  Document the output change for existing users.
- [ ] Verify the selected release revision passes the release checks and the
  other alpha blocker issues are resolved before creating/publishing its tag.
  Record that revision and the verification results in the release notes.

## Implementation notes and decisions

Start with `README.md`, `deps.edn`, `bb.edn`, `src/issues/query.clj`, renderers,
and `test/issues/ui_test.clj`. Resolve the license, supported runtime versions,
public API scope, and final role-key names as explicit release decisions.
Filing this issue does not itself choose a license or publish a release.

## Log

- 2026-09-04: Filed from the alpha readiness review; implementation not started.
