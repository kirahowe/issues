# Show an issue's status the same in every workspace

## Context

An issue's status lives in its `.edn` file, which is versioned with the
code, so each jj workspace or git worktree has its own copy. A status
change made in one workspace is invisible everywhere else until that
commit lands and the other copies update.

Seen on 2026-10-24 in relay: an agent verified eight issues and ran
`issues --dir ~/code/projects/.next/verify/relay done <n>` for each, in
the `verify-review` workspace. The commit "Close the eight issues
verified on main" (`ypqzonvs`) never reached main. The project root's
`.issues` still says `review` for all eight, so `issues list` in the root,
`issues attention`, and overview (which reads the root) all show work
waiting on Kira that is already finished.

The same split happens in the other direction. An agent that runs `issues
start` in its workspace looks idle to everything that reads the root.

Status is coordination state. The code it describes is versioned per
workspace, but everyone needs to agree on the status now. Today every
reader picks a copy, and the root is usually not the one an agent
changed.

## Acceptance criteria

- [ ] Kira has decided how this works. Two options:
      (a) lifecycle state (status, and maybe priority and blockers) moves
      out of the versioned files into one store per project that every
      workspace shares, while titles and details stay versioned; or
      (b) status stays versioned, and readers resolve it as trunk's value
      overlaid by every live workspace's uncommitted or unlanded change,
      with the workspace named.
- [ ] A status change made in any workspace or worktree of a project
      shows in `issues list`, `attention` and `next` from the root and
      from every other workspace, without landing first.
- [ ] Two workspaces changing one issue's status do not lose either
      change silently. The later one wins with a record, or the conflict
      is reported.
- [ ] `doctor` reports a status that disagrees between workspaces, if
      option (b) leaves that possible.
- [ ] The issues skill says where status is read and written, so agents
      in workspaces need no special handling.

## Plan

Related: issues#2 (renumber after a workspace lands) runs into the same
per-workspace split for ids. overview reads `issues list` from a
project's root and gets this for free once the CLI resolves it.

## Log
