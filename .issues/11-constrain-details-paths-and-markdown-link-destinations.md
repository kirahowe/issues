# Constrain details paths and Markdown link destinations

## Context

The alpha review confirmed that `:details "../escaped.md"` is accepted and
`issues details 1` creates the referenced file outside `.issues/`. Reading details
follows the same unrestricted path. Validation currently requires only a
nonblank string, despite the documented convention that details live beside
issue metadata.

The Markdown renderer also preserves executable link destinations. Rendering
``[click](javascript:alert`1`)`` produces an anchor with that `javascript:` href.
HTML escaping does not validate URL schemes, so the current namespace comment
claiming untrusted content is safe is too broad.

## Acceptance criteria

- [ ] Define and enforce the details-file boundary under the issue's `.issues/`
  directory for reads, creation, editing, snapshots, and UI rendering.
- [ ] Reject traversal and absolute paths that escape that boundary, including
  symlink escapes; ensure validation also works when the target does not exist.
- [ ] `doctor` reports invalid details paths without reading or creating their
  targets; mutation commands return clear errors before touching outside files.
- [ ] Validate Markdown link destinations against an explicit safe policy. Keep
  intended ordinary links usable and render unsafe destinations inert.
- [ ] Reject executable schemes including `javascript:` and unsafe `data:` URLs,
  including case/control-character variants relevant to URL interpretation.
- [ ] Add regressions for outside-path reads and writes, symlink escapes,
  legitimate details files, unsafe hrefs, and ordinary supported links.
- [ ] Update relevant documentation and the renderer's safety claim to describe
  the actual guarantees.

## Implementation notes

Inspect `issue/validate`, `store/details-path`, `ensure-details!`, `read-details`,
and `markdown/inline`. Use one shared path-resolution policy across callers so
CLI and UI behavior cannot drift. Existing HTML-string escaping tests do not
cover URL scheme handling.

## Log

- 2026-09-04: Filed from the alpha readiness review; implementation not started.
