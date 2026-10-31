# High-Level Design: kaocha-sublime

## Problem

Clojure work with coding agents is bottlenecked on test feedback. A cold `clojure -M:test` costs 15-60 s before the first assertion, so agents either wait (and batch large, risky changes) or skip checks. Several agents working in one checkout multiply the cost and the machine load. And agents act on test output literally: a stale "pass", an ambiguous status line, or a lost failure message sends them confidently in the wrong direction.

## Approach

### One owned warm test JVM per project root

`warm-jvm` starts a test-only JVM for a project root (a directory with `deps.edn`), and `test-focus` runs named namespaces or vars in it after reloading changed code. Checks take seconds. The JVM is fingerprinted by its runner, aliases and parsed dependencies; when any of those change, the next check restarts it once and says so (`COLD START`).

### Receipts an agent can trust

Every run writes an atomic receipt (`target/kaocha-runs/<id>.edn`, published through `LATEST`), and every `test-focus` check prints one JSON line with the verdict, counts and per-failure details (test var, file:line, expected/actual, the test's own output). A run that did not complete has no verdict.

### The warm loop is the inner loop; a cold run is the gate

Warm checks are for iteration. A cold full-suite run remains the commit gate, because a long-lived JVM can hide stale definitions that only a fresh JVM exposes.

## Target Users

- **Coding agents** iterating on Clojure code: they need second-scale checks, unambiguous verdicts, and failure details in the output itself.
- **The coordinating session** that owns the JVM for a batch of agents and runs the cold gate.
- **Gene**, reading receipts and timings to judge whether the loop and the machine are healthy.

## Goals

- A focused check returns in a few seconds on a warm JVM.
- No receipt reports `pass` for code that was not actually loaded and run.
- One cold start per root per batch, with every further cold start explained by its receipt.
- A failure is diagnosable from the check's own output, without opening other files.

## Non-Goals

- Replacing the cold full suite as the commit gate.
- Coverage or mutation runs (instrumentation distorts a warm JVM; those belong to separate tools).
- Managing JVMs for roots other than the one named, or the application's own runtime JVM.

## Tenets

Ordered: when two conflict, the higher one wins.

- **Never a false green.** When the loop cannot prove a verdict (stale code, failed reload, unresponsive JVM), it reports `fail` or `unverified` with its evidence, never `pass`, and never a guess.
- **Warm is the inner loop, cold is the gate.** Prefer fast warm checks for iteration, and keep one cold run as the final word before a commit.
- **Additive output.** Receipts and the JSON line are read by other programs and agents; new information goes into new fields, and existing fields keep their names and meanings.

## System Design

| Component | Intent family (`docs/intent/registry.edn`) | Owns |
|---|---|---|
| Run receipts and `LATEST` | `STATUS-*` | Atomic receipts, the pointer as the only verdict entrance, crash and closure semantics |
| Agent reporter and admission | `AGENT-*` | Content-bound status, heap and image admission, refusal IDs, probe reloads, scope |
| Warm loop (`warm-jvm`, `test-focus`, `sublime-loop`) | `LOOP-*` | JVM ownership and fingerprint, focused checks, failure details, probes under load, reload failures |

`bin/sublime-loop` (Python) owns process lifecycle, fingerprinting and the JSON receipt; `src/kaocha_sublime/runner.clj` runs inside the warm JVM and reloads, selects and executes tests.

## Key Design Decisions

| Decision | Chosen | Alternatives | Rationale |
|---|---|---|---|
| Intent records | One append-only `docs/intent/registry.edn`, each intent with EARS text, misreadings, boundaries, linked tests and rationale, enforced by `intent_contract_test` | Per-topic spec files | Misreadings and boundaries are first-class here because the readers are agents; one file keeps IDs globally unique and append-only. |
| Environment fingerprint | Parsed `deps.edn` (used aliases and non-alias keys), `tests.edn`, runner sources | File bytes | Comments and whitespace must not cost a cold start; real dependency changes must. |
| Liveness under load | Load-scaled probe with retries; on exhaustion report `unverified` | Fixed timeout, then restart | A slow machine is not a dead JVM; restarting a live JVM doubles the load that caused the slowness. |

## Success Metrics

- Warm focused checks of a few seconds on a quiet machine; cold starts per batch countable from receipts.
- Falsification: any receipt with `pass` for code that failed to reload; a `COLD START` whose receipt gives no reason; a failure whose output omits the failing test.

## References

- `docs/SETUP.md` — adoption, agent rules (§13), pitfalls and recipes (§14).
- `docs/intent/registry.edn` — the requirement layer (EARS, append-only).
- `docs/intent/<topic>/` — design documents for features that need design before code.
