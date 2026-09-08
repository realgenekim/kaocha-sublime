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

Every completed run writes `<id>.edn.tmp`, then atomically renames it to
`<id>.edn`. A killed or aborted run has no completed receipt. IDs default to
UTC plus UUID. `KAOCHA_RUN_ID` overrides the ID and must be unique for each run;
reuse or unsafe path characters fail loudly. Files are never overwritten.
The last line of normal run output is `RUN-RECEIPT <absolute path>`.
Watch lifecycle messages can follow this marker.

Receipts contain UTC timestamps, elapsed milliseconds, test/assertion totals,
failure details, a SHA256 config hash, Git HEAD when available, JVM PID,
Kaocha/plugin versions, and selection data. Expected and actual values are
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

Release work: choose a license and published Maven/Git coordinates after the
remote exists; upstream the summary-path adapter; test a supported Kaocha version
matrix and other plugin ordering/reporters; set a release version and CI gates.
No artifact has been published.
