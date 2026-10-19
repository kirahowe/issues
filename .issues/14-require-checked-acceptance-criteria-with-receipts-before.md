# Require checked acceptance criteria with receipts before review

## Context

Issues reached Kira's review with nothing but a log line to go on. Kira
wants agents to check off the acceptance criteria and attach receipts for
the ones they claim are done, so the review starts from evidence.

## Acceptance criteria

- [x] Criteria are parsed from `## Acceptance criteria` with nested
      `- Receipt:` lines, including receipts that wrap onto a second line
  - Receipt: src/issues/criteria.clj; `bb test issues.criteria-test` passed,
    3 tests, 0 failures
- [x] `issues review` and `issues set <id> status review` refuse while a
      criterion is unchecked or has no receipt, or none are listed, and name
      each gap; `--force` skips the check
  - Receipt: `cli-test/review-needs-receipts`; `bb test issues.cli-test`
    passed, 21 tests, 0 failures
  - Receipt: `bb issues review 14` on this issue before these details existed
    printed "no details file with acceptance criteria" and exited 1
- [x] The refusal reaches the terminal
  - Receipt: stderr flush fix in commit "Flush stderr before exiting", with
    `cli-test/errors-reach-stderr-from-a-real-process`
- [x] The review inbox shows each issue's receipted count, marked `!` when
      something is missing
  - Receipt: `tui-test/criteria-cell` and the list assertion in
    `tui-test/rendering`; `bb test issues.tui-test` passed, 6 tests, 0 failures
- [x] The skill tells agents how to write receipts and when `--force` is
      acceptable; the README describes the gate
  - Receipt: skills/issues/SKILL.md steps 2 and 4; README "How it works"
- [x] This repo's own issue in review carries receipts
  - Receipt: issues#13 passed `bb issues review 13` with no `--force`

## Log

- 2026-10-19 filed and implemented; `bb test` passed, 146 tests, 0 failures;
  lint and format checks clean
