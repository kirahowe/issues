# Let agents close issues without waiting on Kira's review

## Context

The issues skill makes human acceptance the normal boundary for `done`.
An agent finishes, moves the issue to `review`, and stops there. Kira does
not need to review every issue, so almost nothing reaches `done` and the
review column keeps growing.

This now matters in overview as well. overview groups sessions under
issues as workstreams, and a workstream ends when its issue is done or
dropped. Issues stuck in `review` keep their workstreams in the list and
count as needing Kira, which hides the ones that really do.

#14 already makes agents check off every acceptance criterion with a
receipt before review. So by the time an issue reaches review, the
evidence for closing it is already written down.

## Proposed direction

Discussed with Kira on 2026-10-23. Not settled yet.

- An agent closes an issue itself once every acceptance criterion is
  checked with a receipt.
- It stops at `review` only for issues marked as needing Kira, or when it
  is unsure.
- Kira gets a way to look over what closed recently without anything
  waiting on Kira. overview could list the workstreams done today.

## Open questions

- How an issue is marked as needing Kira: a tag such as `needs-kira`, a
  field, a type, or a priority threshold. And who sets the mark: Kira
  when filing, or the agent when it judges a change risky.
- Whether `issues done` should run the same receipt check that
  `issues review` runs now, so an agent cannot close an issue on nothing.
- What "unsure" means, concretely enough for the skill to say it.
- Whether an issue closed without review should be recorded as such, for
  example with a `closed-by` field, so a later look-over can find it.

## Acceptance criteria

- [ ] The issues skill tells agents when to close an issue themselves and
      when to stop at review, and how to tell the two apart.
- [ ] An issue can be marked as needing Kira's review, and `list` and
      `show` show the mark.
- [ ] `issues done` refuses an issue whose criteria are not all checked
      with receipts, unless forced, matching the review gate.
- [ ] Kira can list the issues closed in a given period, for example
      `issues list --closed --since 1d`, and see which of them skipped
      review.
- [ ] The README describes the workflow.

## Plan

## Log

- 2026-10-24 filed from the overview workstreams session.
