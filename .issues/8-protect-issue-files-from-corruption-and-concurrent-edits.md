# Protect issue files from corruption and concurrent edits

## Context

The alpha release review on 2026-09-04 confirmed three data-integrity bugs in
`src/issues/store.clj`:

- A status change on malformed EDN overwrites its original contents. For example,
  `done 1` on an unterminated issue map replaced the source with only its id,
  status, and updated date, then failed while regenerating the index.
- Concurrent creates with different titles can both allocate the same numeric
  id. `next-id` computes max+1, but exclusive creation reserves the full slugged
  filename: `1-alpha.edn` and `1-beta.edn` do not collide. A synchronized
  reproduction confirmed both calls returned id 1.
- Concurrent updates read the same original record and silently lose changes.
  A synchronized status/priority update reproduction returned success for both
  writes but retained only the priority change.

`find-issue` also chooses one file when an id is duplicated, allowing ambiguous
mutations. These are release blockers for humans and agents sharing a tracker.

## Acceptance criteria

- [ ] Refuse ordinary mutations of unreadable records and preserve their original
  bytes; return an actionable error identifying the file.
- [ ] Refuse mutations by id when more than one issue file has that id.
- [ ] Concurrent creates allocate unique numeric ids regardless of title/slug.
- [ ] Concurrent updates preserve both changes or explicitly reject a conflicting
  update; never silently acknowledge a lost change.
- [ ] Replace issue files atomically so a failed replacement leaves the prior
  complete record intact. Review details and generated-index writes for the same
  partial-write and concurrent-write hazards.
- [ ] Coordinate allocation and read-modify-write operations across CLI processes
  using a lock or equivalent mechanism; document its scope and failure behavior.
- [ ] Add deterministic regression coverage for different-slug allocation races,
  concurrent updates, duplicate-id refusal, malformed-source preservation, and
  failed writes. Verify index regeneration remains consistent with stored issues.

## Implementation notes

Start with `next-id`, `attempt-create!`, `create!`, `find-issue`, `update!`, and
`write-issue!` in `src/issues/store.clj`. Existing allocation tests force the same
slug and therefore miss the different-slug race. Atomic replacement alone does
not prevent lost updates: protect the entire read-modify-write operation.

## Log

- 2026-09-04: Filed from the alpha readiness review; implementation not started.
