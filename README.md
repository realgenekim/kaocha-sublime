# kaocha-sublime

Two experimental plugins for **Kaocha 1.91.1392**, Clojure 1.12, Java 17+:
atomic run receipts and conservative selection of affected test namespaces.

Add the local checkout to your test alias:

```clojure
:extra-deps {local/kaocha-sublime
             {:local/root "/home/forge/src/kaocha-sublime"}}
```

Enable both in `tests.edn`, after your other plugins:

```clojure
#kaocha/v1
{:plugins [:kaocha.plugin/affected :kaocha.plugin/run-receipt]
 :kaocha.plugin.run-receipt/dir "target/kaocha-runs/"
 :kaocha.plugin.run-receipt/include-tests? true}
```

Run your normal Kaocha command with `--watch`, or add
`--affected-since HEAD` (a commit SHA works too). `--affected-since :tracker`
requires watch tracker state; absent state produces a loud full run.
A plain run without watch or this option runs the configured suite normally.

Every run reserves `<id>.edn.tmp` while in progress. Completion writes
`<id>.edn.ready`, atomically renames it to `<id>.edn`, then atomically publishes
`LATEST` (temporary file plus rename) before removing the running reservation.
`LATEST` contains `{:path <absolute-receipt-path> :run-id <id> :finished <UTC>}`.
A killed test has no completed verdict. IDs default to
UTC plus UUID. `KAOCHA_RUN_ID` overrides the ID and must be unique for each run;
reuse or unsafe path characters fail loudly. Files are never overwritten.
The last line of normal run output is `RUN-RECEIPT <absolute path>`.
Watch lifecycle messages can follow this marker.

Receipts contain UTC timestamps, elapsed milliseconds, test/assertion totals,
failure details, a SHA256 config hash, Git HEAD when available, JVM PID,
Kaocha/plugin versions, selection data, `:trigger {:files [...] :at <UTC>}`,
and `:auto-followup?` (boolean). Watch triggers contain canonical paths reported
by Kaocha's actual queue, including batched events. Initial runs and Enter have
empty file lists; only Kaocha's automatic rerun branch sets `:auto-followup? true`.
The trigger stamp records when that batch was accepted for rescan. Expected and actual values are
printed strings so arbitrary test objects remain safe to read with `clojure.edn`.
Optional test details include outcomes and Kaocha's captured output (stdout and
stderr share its capture buffer). Keep retention policy outside the test runner.

`kaocha.api/run` and `kaocha.repl/run` both return
`:kaocha.plugin.run-receipt/path`. The latter needs a narrow, idempotent adapter
around Kaocha's totals reduction: upstream discards extra keys. It preserves
paths only on tagged suite results and refuses untested Kaocha versions.

A coldstart harness can put this line in its boot block after its initial probe:

```text
TEST-RECEIPT /absolute/worktree/target/kaocha-runs/<actual-run-id>.edn
```

Use the returned `:kaocha.plugin.run-receipt/path` value to fill it. Do not infer
a receipt from a timestamp or choose the newest file from another run.

## watch mode for agents: never tail; ask kaocha-status

With Babashka on PATH, run this one-shot command from the project worktree
(use the absolute path to this checkout's `bin/kaocha-status` if needed):

```bash
/home/forge/src/kaocha-sublime/bin/kaocha-status \
  --dir target/kaocha-runs/ \
  --since test/my_app/core_test.clj \
  --ns my-app.core-test
```

The exact three-question rule is **in progress? mine? passed?**

1. **In progress?** `RUNNING` means a run has been reserved, including watcher
   rescan and namespace reload. Ask again for a verdict. Never tail the logs.
2. **Mine?** Supply `--since <file>` for the save just made, or an ISO-8601 UTC
   stamp, and `--ns <ns,...>` for the needed namespaces. `STALE` means the run
   started before that save. `NONE` means there is no usable current verdict.
   Require `DONE` with `covers=yes` when namespace coverage matters.
3. **Passed?** On that `DONE` line, check `fail=0 error=0` and the expected test
   count. `DONE` itself means completed, including failing runs. The receipt has
   assertion, pending, and failure details if they are needed.

Each call prints exactly one line:

```text
KAOCHA-STATUS RUNNING since=<stamp> tmp=<path>
KAOCHA-STATUS DONE id=<id> finished=<stamp> tests=<n> pass=<n> fail=<n> error=<n> closure=<n>/<total> covers=<yes|no|unknown> receipt=<path>
KAOCHA-STATUS STALE latest=<stamp> newer_edit=<path>@<mtime>
KAOCHA-STATUS NONE dir=<dir>
```

`--dir` defaults to `target/kaocha-runs/`. `closure=n/total` is selected/configured
test namespace counts; `covers` checks that **all** requested namespaces occur in
the receipt's recorded dependency closure. It is `unknown` without a namespace
request or closure metadata. Missing selection counts display `0/0`.
For `--since <stamp>`, the stale field is `newer_edit=stamp@<stamp>`.
Whitespace and percent signs in paths are percent-encoded to preserve one line.
The command reads `LATEST`; it never chooses a receipt by filename or mtime.
Old receipt directories without a pointer return
`KAOCHA-STATUS NONE dir=<dir> reason=no-latest-pointer`. Invalid pointers and
ambiguous concurrent runs also produce `NONE` with a reason. Normal status states
exit zero; invalid arguments/data exit 2 with one `NONE ... reason=invalid-input` line.

An automatic follow-up has its own receipt and updates `LATEST`. It also records
`:followup-of <parent-receipt-path>`. With `--since`, an **unchanged** automatic
follow-up resolves that explicit parent, preserving the save's run ID and verdict.
Without `--since`, status shows the latest follow-up. A follow-up that drained new
file events is a new save's run and is evaluated on its own start time. This is a
freshness check against the latest event run, not a search for arbitrary past saves.
Retain a returned receipt path when an exact historical run is needed.

The two limits:

- **`sed -i` blindness is upstream.** A rename-style edit may produce no event that
  Kaocha consumes. `--since <file>` returns STALE; it cannot make the watcher rerun.
  Use a watched in-place save or request a manual run with Enter.
- **An agent that edits during a run gets STALE by design.** Freshness compares the
  run's **start**, including rescan, against the save's precise file mtime. A later
  finish stamp cannot prove the run included that edit. Wait for a new run.

After a watcher dies, its `.tmp` still says RUNNING until explicit cleanup:

```bash
/home/forge/src/kaocha-sublime/bin/kaocha-reap --dir target/kaocha-runs/
```

The reaper never signals a process. It checks the recorded local PID and process
start time, leaves live/unknown owners alone, and publishes `INTERRUPTED` before
removing dead reservations. Status then says `NONE ... reason=interrupted` instead
of resurfacing the old green. A later completed run supersedes that interruption.
Use one local watcher per receipt directory; do not manually discard crash markers.

The status executable loads only dependency-free Clojure under Babashka; it starts
no JVM or watcher. Ten fresh processes against the live mvr watcher measured
**23.85 ms median, 25.57 ms maximum** (Babashka 1.13.219, this Linux seat).
The [witnesses](evidence/status-watch/witnesses.jsonl) retain each line and timing.
The watcher adapter is pinned to Kaocha 1.91.1392, observes its queue/rescan
functions, and reserves RUNNING before rescan. It requires new validation before
supporting a different upstream lifecycle.

## Affected selection and validation

Selection uses Kaocha's own tools.namespace graph, includes changed test
namespaces, and excludes unrelated namespace groups before execution. An initial
watch run is full. Unknown inputs, resources/configuration changes, missing graph
coverage, deleted files, scan errors and unsupported test types fall back loudly.
A content snapshot covers the configured source/test roots, resources, `deps.edn`
and `tests.edn`; add other file roots with `:kaocha.plugin.affected/paths`.
The specimen configurations do not watch root-level `deps.edn`/`tests.edn`
edits themselves: these enter the snapshot at the next watched event. Use the
explicit SHA option for such changes, or configure upstream watch coverage.
Kaocha reloads before test selection, so source reload cost remains. The immediate
unchanged watch follow-up after focused green reuses the selection once; Enter
subsequently requests a full run. Existing explicit test filters still apply.

The receipt limitation is its upstream integration boundary: the REPL adapter
is version-pinned and the end marker cannot control subsequent watcher, plugin,
or background-thread output. The selection limitation is static dependency
coverage: runtime-only coupling and an edit that the upstream watcher never
signals cannot be inferred. Use declared namespace dependencies and editor saves
that generate watch events; disable narrowing when static coverage is inadequate.
Use one JVM per worktree; concurrent Kaocha runs in one JVM are unsupported.

Validation: `clojure -M:test`, `python3 scripts/process_witnesses.py`,
`python3 scripts/cli_witnesses.py`, and `~/bin/clj-kondo --lint src test`.
Verify the retained run artifacts without recreating worktrees with
`clojure -M scripts/verify_evidence.clj`.
The process witnesses use `/var/tmp/forge/kaocha-sublime-fx/` and kill exact PIDs.
The stopwatch harness is adapted from the supplied baseline; raw evidence and
its limitations live in `evidence/` and `docs/plan.md`.
The normal suite includes the bidirectional
[linked-intent contract](docs/intent/registry.edn) and status state-space tests;
Babashka is required. `scripts/status_watch_witness.py` runs the real-watcher
acceptance sequence in a fresh mvr fixture under `/var/tmp/forge/kaocha-status-fx/`.

Release work: choose a license and published Maven/Git coordinates after the
remote exists; upstream the summary-path adapter; test a supported Kaocha version
matrix and other plugin ordering/reporters; set a release version and CI gates.
No artifact has been published.
