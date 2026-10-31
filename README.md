# kaocha-sublime

**Adopting the warm test loop (`warm-jvm`, `test-focus`) in a repo or worktree:
see [docs/SETUP.md](docs/SETUP.md).**

Experimental plugins for **Kaocha 1.91.1392**, Clojure 1.12, Java 17+:
atomic run receipts, conservative affected namespace selection, and an agent reporter
with content-bound status. Exact agent image attestation currently requires Linux
inode change-time support; unsupported filesystems remain identity unknown.

Add the local checkout and explicit heap options to your test alias:

```clojure
:extra-deps {local/kaocha-sublime
             {:local/root "/home/forge/src/kaocha-sublime"}}
:jvm-opts ["-Xms64m" "-Xmx512m"]
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
Admission refusals, including heap-cap refusal before loading, honor the same
`KAOCHA_RUN_ID` and publish to `<id>.events`; launchers need no directory diff.
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

For direct `kaocha.api/run` in agent mode, require
`kaocha.plugin.run-receipt` before the first call so its whole-run `finally`
adapter is installed. CLI and REPL configuration already load the plugin before
running. A first direct call that lazily loads the plugin from inside `api/run`
refuses before acquiring a lease; subsequent calls use the installed adapter.
Aborted agent runs retain typed `STATUS refused/protocol-error` evidence and
release their leases, including on load errors and interrupts. Under the
directory lock, a new run reclaims dead PID/start reservations, preserves the
`INTERRUPTED` tombstone, and emits `STATUS killed/process-dead` for each reclaimed
run ID in its stream. Readers continue to the new run's own completion record.

A coldstart harness can put this line in its boot block after its initial probe:

```text
TEST-RECEIPT /absolute/worktree/target/kaocha-runs/<actual-run-id>.edn
```

Use the returned `:kaocha.plugin.run-receipt/path` value to fill it. Do not infer
a receipt from a timestamp or choose the newest file from another run.

## Agent view: save, mark, follow

Enable `:kaocha.plugin/run-receipt` after the other plugins, then select
`:reporter [kaocha.report/agent]` in `tests.edn` or pass
`--reporter kaocha.report/agent`. `KAOCHA_AGENT=1` also selects this view when the
receipt plugin is enabled. Every launcher must supply `-Xms64m -Xmx512m`;
agent mode checks JVM input arguments and effective maximum heap before loading
any tests, refusing missing/oversized cap evidence with `STATUS refused/heap-cap`.
The default policy ceiling is 536870912 bytes; configure it with
`:kaocha.plugin.run-receipt/heap-ceiling-bytes`.

From the project worktree, after saving:

```bash
/home/forge/src/kaocha-sublime/bin/kaocha-status --mark test/my_app/core_test.clj
/home/forge/src/kaocha-sublime/bin/kaocha-status --follow --token TOKEN_FROM_MARK --timeout 120
```

MARK prints one `STATUS` JSON record containing an opaque `token`. Its manifest
binds the canonical workspace, runner session, configuration/classpath generation,
content hashes, deleted requested paths, and required scope. Identical candidates
and scopes coalesce. MARK launches no JVM. By default the required scope is the
full configured suite; add `--ns my-app.core-test,another-test` **to MARK** to bind
an explicit namespace scope. Namespace scopes require all tests in each requested
namespace under the declared gate, including tests a focused retry skipped.
Declared `:skip-meta` exclusions count as covered and are recorded with their
matching rules in the receipt; they never add to passing assertion counts.

FOLLOW blocks in one Babashka process and prints exactly one matching `RUN-DONE`
or one `STATUS`. It exits **0** for an exact candidate with required coverage and
pass, **1** for verified red on an exact candidate, **2** for unknown, incomplete,
uncovered, superseded, killed, refused, or timeout. Verified red does not claim
that every required check finished. `--token TOKEN` without `--follow` makes one
current observation. `--dir` defaults to `target/kaocha-runs/`; no heartbeat is
emitted. Timeout ends the wait and never kills or replaces the JVM.

An overwrite gives `superseded`; a requested path outside the runner's watched
roots gives `uncovered`. A run that finished before MARK can match by content.
An old LATEST green cannot satisfy a different candidate. PID **and start identity**
identify a killed active run; status reports death without requiring cleanup.
`bin/kaocha-reap --dir DIR` preserves that interrupted identity while retiring dead
reservations. An unchanged automatic follow-up carries a parent ID and digest;
status selects its matching primary. Runs are serialized per JVM and receipt directory.

Runner events live in `<id>.events`, an owned destination separate from test
stdout/stderr. A clean run emits `RUN-START` and `RUN-DONE`; FAIL records are bounded
to 20 events and 16 KiB per run. DONE reports shown/omitted counts. START precedes
load; DONE follows final receipt rename and LATEST publication. Status reconstructs
that identical DONE from the authenticated receipt, so lost terminal output does
not require another test run. Captured test output is bounded to 16 KiB per test
and 64 KiB in the receipt's diagnostic artifact. Capture bypass, JVM diagnostics,
and launcher output remain the launcher's separate diagnostic stream.

Cold `kaocha.runner` uses `mode=gate`; warm `kaocha.repl/run` uses `mode=probe`;
watch uses `mode=watch`. A fresh uninstrumented warm image is **identity unknown**.

Warm adapters should use the supported reload-and-run entrance:

```clojure
(require '[kaocha-sublime.probe :as probe])
(probe/run '[my-app.core my-app.core-test] {:suite :unit})
```

Relay only the returned `:events` file and use `:exit` for the run's outcome.
`:path` is the completed receipt, or nil on an aborted load; the returned `:error`
belongs in diagnostics. Reservation and START precede reload, so mistyped
namespaces produce typed load evidence and leave the next probe usable.
This entrance unloads namespaces using the watcher's tools.namespace machinery
before requiring them; deleted Vars therefore disappear. Supply source
namespaces before their test/dependent namespaces. It does not discover or
reload unnamed dependents, repair retained object/Var references elsewhere, or
attest a whole warm image. Reload-disabled namespaces refuse. Probe identity
remains unknown; token acceptance still requires the independent image proof.
Plain `require :reload` and the legacy adapter retain Clojure's stale-Var behavior.

Hash equality alone never attests its already-loaded Vars. Watch binds candidate
hashes to the actual namespace reload and observed generation. Inode change times
also reject transient save-and-restore during load/run. Config/classpath changes
invalidate the warm image; restart through the bounded launcher. External REPL
adapters must call `kaocha-sublime.image/invalidate!` after untracked evaluation
that may change project Vars. This is a cooperative runner contract, not isolation
from arbitrary code in the same JVM.

`--since FILE|STAMP` remains a compatibility freshness hint and returns identity
unknown (exit 2). The old mtime-only text is available explicitly with `--legacy`
for human tooling; its exit status is not an agent acceptance contract. The
[grammar and migration contract](docs/agent-v1.md) specifies every field and the
PROBE/GATE adapter boundary. Never infer a verdict from raw stdout.

Kaocha's watcher still determines which filesystem events schedule a run. It can
miss rename-style saves and ignores delete notifications. MARK/FOLLOW will not
manufacture a run: use a watched in-place save, Enter, or an explicit bounded run.
Hash-detected changes are fed into the existing reload tracker, including
same-mtime changes and removal of isolated namespaces.

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

Validation: `clojure -J-Xms64m -J-Xmx512m -M:test`, `python3 scripts/process_witnesses.py`,
`python3 scripts/cli_witnesses.py`, and `~/bin/clj-kondo --lint src test`.
Verify the retained run artifacts without recreating worktrees with
`clojure -J-Xms64m -J-Xmx512m -M scripts/verify_evidence.clj`.
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
No Maven artifact has been published.

Agent process witnesses: `python3 scripts/agent_witnesses.py` and
`python3 scripts/agent_watch_witness.py`. The MVR ceiling witness is
`python3 scripts/agent_mvr_witness.py` (an isolated, fresh fixture is required).
Each witness JVM uses `-Xms64m -Xmx512m`; raw results are retained under
`evidence/agent-v1/`. These are correctness and absolute wall/byte measurements,
not a matched speedup claim.

## Warm runner (sublime-loop)

`bin/sublime-loop` keeps one owned, test-only nREPL JVM per project root and
checks named namespaces in it. Repos call it from here, through a
`~/bin/sublime-loop` launcher or a `$(SUBLIME_LOOP)` make variable:

```sh
sublime-loop --root <dir> up            # boot (or attest) the runner
sublime-loop --root <dir> check NS...   # reload the dependency closure, run NS
sublime-loop --root <dir> check NS/VAR  # same, but run only that test var
sublime-loop --root <dir> status
sublime-loop --root <dir> down
```

`--root` (default: the current directory) is the directory with `deps.edn`. All
state lives in `<root>/.dev-loop/`. `--aliases` (default `:test`, plus `:sublime`
when the repo defines it) names the project's deps aliases; the script always appends
its own `-Sdeps` alias `:kaocha-sublime/library` (this checkout by absolute path,
nrepl, tools.namespace, `-m kaocha-sublime.runner`), so no repo alias names the library; `--env KEY=VALUE` adds runner environment, where
`{state_dir}` expands to `<root>/.dev-loop`. Every command prints one
`dev-loop.v1` JSON receipt and writes it to `result-<uuid>.json` and
`last-result.json`. Exit 0 = ready/stopped/pass, 1 = fail/stale/tainted, 2 = unknown.
A failed `check` receipt carries `failures` (each failing assertion: test var, `file:line`,
message, `testing` context, expression, bounded `expected`/`actual`, and the test's captured
stdout/stderr tail) and `failures-omitted`, and prints a readable block of the same on
stderr after the JSON line. Field reference: docs/SETUP.md section 7.

A repo needs no alias for the library. An optional `:sublime` alias carries only
project extras (for example `:extra-paths ["dev"]`); see docs/SETUP.md. `bin/warm-jvm`
(= `up`) and `bin/test-focus` (= `check`, with a loud `COLD START` restart) wrap the
CLI. Optional Makefile lines:

```make
sublime-up: ; $(SUBLIME_LOOP) --root . up
sublime-status: ; $(SUBLIME_LOOP) --root . status
sublime-check: ; $(SUBLIME_LOOP) --root . check $(NS)
sublime-down: ; $(SUBLIME_LOOP) --root . down
```

A dead runner no longer wedges the loop. Before contacting nREPL, the CLI proves
the recorded pid is a live java whose command line carries the record's generation
and that it owns the recorded port (`lsof`). Otherwise the record is archived to
`session.stale-<pid>-<UTC>.json` (never deleted or overwritten): `up` boots fresh,
`status`/`down` report `stopped` with `stale_record`, and `check` asks for `up`.
A process whose ownership is not proven is never signalled. A live, owned runner
that does not answer nREPL is `unverified` (exit 2): the identity probe waits and retries
in proportion to the 1-minute load per core (bounded), counts a late answer, and otherwise
reports the evidence (pid, port, generation, attempts, load). It keeps its record, is never
reported ready, and no second JVM is started for the root; `down` can still stop it.

The restart fingerprint reads `deps.edn` and `tests.edn` as EDN: comments, whitespace, key
order and aliases the runner does not use do not restart the JVM; `:deps`, `:paths`, other
top-level keys and the used aliases do. A reload error (for example a syntax error) is a
`fail` at `stage: reload` naming the namespace, `file:line` and message; the JVM stays warm,
and the next check reloads the closure again, which repairs the namespace.

Never vendor `bin/dev-loop` or `dev/feedback.clj` into a repo. Improvements land
here, with tests: `python3 -m unittest discover -s test -p 'test_*.py' -v`.
