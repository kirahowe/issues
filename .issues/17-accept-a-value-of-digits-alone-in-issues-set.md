# Accept a value of digits alone in issues set

## Context

`issues set <id> <field> <value>` crashes with a ClassCastException
whenever the value is all digits. Found on 2026-10-25 while linking
relay#35 and relay#83 with `issues set 35 related 83`.

Reproduced in a fresh project with two issues:

| Command | Result |
|---|---|
| `issues set 1 blocked-by 2` | crash |
| `issues set 1 related 2` | crash |
| `issues set 1 title 2026` | crash |
| `issues set 1 tags 7` | crash |
| `issues set 1 blocked-by 2,3` | works |
| `issues set 1 related demo#2` | works |

The message is `java.lang.Long cannot be cast to java.lang.CharSequence`,
with a stack trace that points at `issues.cli/run`.

Cause: the `set` command's spec (`src/issues/cli.clj`, the table entry
`{:cmds ["set"] ... :args->opts [:id :field :value]}`) coerces `:id`
but says nothing about `:value`, so babashka.cli turns `"2"` into the
number 2. `issue/coerce-field` then hands it to `csv-parts`
(`src/issues/issue.clj`), which calls `str/split` on it. A value with a
comma or a `#` stays a string, which is why `2,3` and `demo#2` work.

`blocked-by 2` is the ordinary way to use that field, so this is not
only an odd input. `issues block <id> --on <n>` still works as a way
round it.

## Acceptance criteria

- [ ] `issues set` takes a value of digits alone for every field:
      blocked-by and related store the number, and title and tags keep
      the text.
- [ ] A test runs `set` with an all-digit value for each field.

## Plan

Coerce `:value` to a string in the `set` spec, or turn it back into a
string before `issue/coerce-field`, so every field sees the text the
operator typed.

## Log
