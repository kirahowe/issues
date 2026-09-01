# Simple local UI to see issues at a glance from any project dir

## Context

Kira wants to glance at a project's issues in a browser without leaving the
terminal workflow. The CLI already reads everything, so a read-only local page
over the same data is the smallest thing that works. No build step and no
JavaScript framework: babashka bundles http-kit and hiccup, and the page
re-reads `.issues/` on every request so CLI edits show up on refresh.

## Acceptance criteria

- [x] `issues ui` inside any project with `.issues/` serves on 127.0.0.1 and opens the browser
- [x] Board shows open issues in columns per status; done and dropped collapsed below
- [x] Clicking a card shows the issue's metadata and its rendered details file
- [x] `--all` puts every discovered project on one board, filterable by project
- [x] `--port` and `--no-open` flags
- [x] Tests cover the handler and a real HTTP round trip

## Plan

- `issues.markdown`: minimal markdown to hiccup (headings, paragraphs, lists,
  checklists, fenced code, inline code, bold, links)
- `issues.ui`: board page, issue page, ring handler, `start!`, `open-browser!`
- `cli`: `ui [--port n] [--no-open] [--all]`
- README and SKILL.md mention it

## Log

- 2026-09-01 filed, fleshed out, implemented
