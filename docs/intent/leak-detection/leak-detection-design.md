---
parent: high-level-design
topic: leak-detection
bead: ks-2u5
intents: [LOOP-LEAK-REPORT-009, LOOP-LEAK-VERDICT-010]
status: proposed
---

# Leak detection: background work a test leaves running, charged to that test

## Problem

A test that starts asynchronous work and returns before it finishes leaves that work to land inside whichever test runs next. The next test fails on code it never touched, only under load, and only sometimes. The failure names the victim; nothing names the culprit.

Field case (pdfreader, 2026-10-31, `test/pdfreader/render_pipeline_test.clj`): RENDER-THREAD-001 stubbed `render/render-page-with-cache` and waited until the stub had been *called*. The `logged-future` that called it was still running when the test returned; it then displayed a 4x4 image after the test ended, inside RENDER-THREAD-002, which asserts the previous image stays up. The repair was a drain fixture on `pdfreader.render/current-renders-pending`. The diagnosis took real digging because every signal pointed at the second test.

The warm loop runs tests back to back in one long-lived JVM, so leaked work has more neighbours to land in than a cold run does. This design makes the leak visible at the test that caused it, in the check's own output.

## Scope

In: Kaocha-run `clojure.test` vars in the warm runner (`kaocha-sublime.runner/check!` via `kaocha-sublime.probe/run`), and the same plugin when a project adds it to `tests.edn` for the cold gate. Observation only by default; verdicts unchanged unless a project opts in.

Out: fixing leaks (the drain-fixture recipe in `docs/SETUP.md` §14 is the fix), interrupting or waiting out leaked work, virtual threads, `core.async` dispatch internals, and anything outside the test JVM.

## What "background work" is observable from inside the JVM

Verified on this machine (Clojure 1.12.0, OpenJDK 27, probe script run 2026-10-31) unless marked otherwise.

| Source of work | How a test starts it | What is observable | Verified |
|---|---|---|---|
| `future`, `future-call`, `send-off` | `clojure.core/future-call` submits to `clojure.lang.Agent/soloExecutor` | A `java.util.concurrent.ThreadPoolExecutor`: `getActiveCount`, `getQueue().size()`, `getCompletedTaskCount`, `getTaskCount`, `getPoolSize`. Threads named `clojure-agent-send-off-pool-N`, keep-alive 60 s, so an idle worker lingers after its task | `future-call` source, class, counters, thread name, keep-alive: yes |
| `send` | `clojure.lang.Agent/pooledExecutor` | Same `ThreadPoolExecutor` surface. Thread name pattern `clojure-agent-send-pool-N` | Class and counters: yes. Name pattern: unverified |
| pdfreader's `logged-future` | `pdfreader.background/logged-future-call` wraps `future-call` | Same as `future`; the only difference is exception logging | Source read: yes |
| Raw `Thread`, app executors (`Executors/newFixedThreadPool`, Swing `TimerQueue`, `AWT-EventQueue`) | App code | `Thread/getAllStackTraces` returns every live *platform* thread with its stack; `ThreadMXBean` (`getThreadCount`, `getAllThreadIds`, `getThreadInfo`, `dumpAllThreads`, per-thread CPU time; `isThreadCpuTimeSupported` true here). An executor that is not one of the two Agent pools is not enumerable; only its threads are | Both APIs: yes. Same thread count from both: yes |
| Virtual threads | `Thread/startVirtualThread`, JDK 21+ executors | Invisible to both `Thread/getAllStackTraces` and `ThreadMXBean` (0 seen while one was parked) | Yes |
| Swing EDT queue (`invokeLater` posted, not yet run) | App code | `EventQueue.peekEvent` could show pending events | Unverified; see Open Questions |
| `core.async` go blocks | `go` | A fixed dispatch pool whose threads are long-lived; task counts not reachable through a public API | Unverified |

Two consequences shape the design:

1. **Thread count is not the signal for pooled work.** After a future completes, its worker thread stays alive, parked in `Unsafe.park` for up to 60 s (observed: `TIMED_WAITING`, top frame `Unsafe.park`). A test that happens to be the first to use `future` creates a thread that outlives it legitimately. The executor's `getActiveCount` plus queue size is the in-flight number; the thread set is for *raw* threads only.
2. **Identity, not counts, for threads.** Work started before the test and finishing during it makes counts go down; comparing the set of `Thread.threadId()` values (JDK 19+, present here) before and after gives exactly the threads born in the window and still alive at its end.

## Kaocha hook points (pinned 1.91.1392, read from the jar in `~/.m2`)

`kaocha.plugin/all-hooks` lists: `cli-options config pre-load post-load pre-run post-run wrap-run pre-test post-test pre-report pre-load-test post-load-test post-summary main`.

Facts that decide placement (`kaocha/testable.clj`, `kaocha/type/var.clj`, `kaocha/type/ns.clj`, `kaocha/plugin/capture_output.cljc`):

- `testable/run-testable` runs `:kaocha.hooks/pre-test` on *every* testable (suite, ns, var) before branching, and `:kaocha.hooks/post-test` only on the `:else` branch, after `testable/run` returns. Skipped, pending and load-error testables get no `post-test`.
- `:kaocha.hooks/wrap-run` wraps each testable's `-run` (not `api/run`, as `docs/plan.md` already records). It sees groups as well as leaves.
- A var's `:kaocha.testable/wrap` is a vector of `(fn [t] #(...))`; `kaocha.type.var/-run` applies it with `(reduce #(%2 %1) wrapped-test wrap)`, so later entries are *outermost*. `kaocha.type.ns/-load` puts the `:each` fixtures first; `capture-output`'s `pre-test` conjs its buffer wrapper after them, so capture is outside the fixtures.
- `:once` fixtures run in `kaocha.type.ns/-run`, outside every var-level hook.
- Leaf test: `kaocha.hierarchy/leaf?`.

**Placement:** `pre-test` and `post-test` on leaf testables, exactly as `capture-output` stores `::buffer` in `pre-test` and reads it in `post-test`. The two hooks bracket `testable/run`, which includes the `:each` fixtures: a drain fixture like pdfreader's runs *inside* the window, so a correctly drained test reports clean, and the detector shows the fix working. Work started by a `:once` fixture is in the first var's "before" snapshot and is never charged to a var.

Not `wrap-run`: it also wraps groups, and a group's window covers all its children, which would charge a leak to the namespace instead of the var. Not `:kaocha.testable/wrap`: the result map is not available inside the wrapped fn, and `post-test` already receives the result testable to annotate.

## Detection

### Snapshot (pre-test, leaf only)

Stored on the testable under `:kaocha.plugin.leak-detection/before`:

```clojure
{:nanos      (System/nanoTime)
 :threads    #{thread-id ...}                       ; platform threads alive now
 :executors  {:send-off {:active a :queued q :completed c :submitted t}
              :send     {...}}}
```

Executor fields come from the `ThreadPoolExecutor` reached through `clojure.lang.Agent/soloExecutor` and `Agent/pooledExecutor` *at snapshot time* (an app may install its own via `set-agent-send-off-executor!`; the field is re-read, never cached). `:submitted` is `getTaskCount`; the Javadoc calls `getActiveCount` and `getTaskCount` approximate, so they are read twice and the smaller/larger taken conservatively (see Open Questions). The thread id set comes from `ThreadMXBean.getAllThreadIds` (no stacks: cheap).

### Delta (post-test, leaf only)

1. **Executor in-flight.** For each Agent pool: `submitted-during = submitted-after - submitted-before`; `in-flight-after = active-after + queued-after`. A leak is `submitted-during > 0` and `in-flight-after > in-flight-before`. The pool's live threads whose top frame is *not* the idle-worker signature (`Unsafe.park` ... `ThreadPoolExecutor.getTask`) are reported with name and top non-JDK frame, so the agent sees *what* is still running (`pdfreader.render/run-current-render!`), not just a count.
2. **New raw threads.** `threads-after - threads-before`, minus threads whose name matches the allowlist, minus idle pool workers (same signature). Each survivor is reported with name, `Thread.getState`, daemon flag, and the first stack frame outside `java.*`, `jdk.*`, `sun.*`, `clojure.lang.*`, `clojure.core*`, `kaocha.*`, `nrepl.*` (the same frame filter `runner/throwable-text` already uses).
3. **Attribution forward.** The post-test leak record is kept in plugin state keyed by test id. On the *next* leaf's `pre-test`, if the previous leaf leaked and the in-flight number is still positive, the current testable gets `:kaocha.plugin.leak-detection/inherited {:from <prev-test> :in-flight n}`. In `post-test`, a testable that *failed* and has `:inherited` is reported as possibly contaminated. This is the line that would have ended the pdfreader dig in one read.

No settle window by default. "In flight when the test returned" is the invariant the test violated; whether the work would have finished 1 µs or 500 ms later depends on machine load, which is exactly why the flake was intermittent. A wait would also drain the leak and so change what the next test sees, making the warm loop behave differently from the cold gate. `:grace-ms` is an opt-in second observation for diagnosis only, reported as `:finished-within-grace?`; it never changes the leak record.

### Noise control

- **Baseline per test** (the pre-test snapshot) removes every long-lived thread: AWT, `TimerQueue`, nREPL server and session threads, Kaocha and the runner itself. Only births inside the window count.
- **Idle pool workers** are classified by executor counters and stack signature, never by existence.
- **Allowlist** of thread-name regexes for lazily started JVM/Swing singletons whose first use happens to be inside a test: default `^AWT-`, `^Java2D Disposer`, `^TimerQueue`, `^Common-Cleaner`, `^process reaper`, `^Attach Listener`, `^Reference Handler`, `^Finalizer`, `^Signal Dispatcher`, `^nREPL-`, `^clojure-agent-send(-off)?-pool-` (idle only). Projects extend it in `tests.edn` under `:kaocha.plugin.leak-detection/allow-threads`. An allowlisted birth is still *counted* (`:allowed n`) so the list cannot silently swallow everything.
- **Per-test opt-out** with var metadata `^:kaocha.leak/allow` for a test that deliberately starts a service a later fixture stops. Opt-outs are counted in the receipt (`:leaks-allowed`), never dropped.

## What is reported, and where

Additive only (tenet 3): new keys, existing keys untouched.

**Result tree.** `post-test` assocs `:kaocha.plugin.leak-detection/leaks` on the var's result testable:

```clojure
{:test        :pdfreader.render-pipeline-test/never-renders-on-ui-thread-test
 :executors   {:send-off {:submitted 2 :in-flight 1}}
 :threads     [{:name "clojure-agent-send-off-pool-3" :state "RUNNABLE"
                :at "pdfreader.render/run-current-render! (render.clj:168)"}]
 :new-threads []            ; raw threads born here and alive at exit, after allowlist
 :allowed     0
 :inherited   nil}          ; or {:from <test> :in-flight 1}
```

**Warm receipt** (`check!`, built from the result tree the way `failure-details` is): `:leaks [...]` (one entry per leaking var, in run order, bounded by `failure-limit` with `:leaks-omitted`), `:leak-count n`, and on each entry of `:failures` whose test inherited work, `:contaminated-by "<test>"`. The runner's existing `:failures` schema (LOOP-FAILURE-DETAILS-001) gains one optional field; its meaning for every other entry is unchanged.

**Kaocha receipt** (`kaocha.plugin.run-receipt`): when `:tests` are included, each `test-outcome` map gains an optional `:leaks` key with the same record. Totals are untouched.

**Terminal** (`bin/sublime-loop`, stderr, same place as `render_failures`), on every check with a non-zero leak count, pass or fail:

```
test-focus: LEAKS 1 -- background work still running when a test returned
  LEAK pdfreader.render-pipeline-test/never-renders-on-ui-thread-test  send-off: 1 in flight of 2 submitted
    running: clojure-agent-send-off-pool-3 at pdfreader.render/run-current-render! (render.clj:168)
  FAIL pdfreader.render-pipeline-test/pending-render-keeps-previous-image-test
    possibly contaminated by never-renders-on-ui-thread-test (1 task in flight when this test started)
```

Nothing goes to stdout: the JSON line stays the only stdout.

## Fail, or warn

Tenet 1 is about *provable verdicts*. A leak does not make the leaking test's own assertions unprovable; its verdict stands. It puts the *next* test's verdict at risk, and that risk is now written down beside the next test's failure. So:

- **Default: warn.** Status, counts and exit code are unchanged; `:leak-count` and the stderr block carry the signal. A red check for every first-use thread birth would make agents chase ghosts and cost cold starts; false red is a cost the tenets do not ask us to pay.
- **Opt-in strict** (`:kaocha.plugin.leak-detection/fail-on-leak true` in `tests.edn`, or `test-focus --fail-on-leak`): `post-test` adds a `{:type :fail :message "background work still running when the test returned" ...}` event to the leaking var and increments `:kaocha.result/fail`, the same place `kaocha.type.var/-run` adds `::zero-assertions`. The failure then flows through `failure-details` and `render_failures` with no new code. This is the right default for a project that has adopted drain fixtures and wants the class ratcheted (house rule 19).
- **`repeat` mode** (LOOP-REPEAT-007) gains one line: a test that flips between runs and inherited work in the failing run is labelled `flaky (contaminated)` rather than plain `flaky`.

## Cost per test

- Pre-test: `getAllThreadIds` (one native call, no stacks) plus six executor counters. Sub-millisecond for the 20–60 threads a Swing app JVM carries.
- Post-test, clean: the same plus a set difference. Sub-millisecond.
- Post-test, leak found: stack capture for the *new or active* threads only (`getThreadInfo(ids, depth)`), not for all threads. A few milliseconds.
- Memory: one small map per leaf, dropped after the receipt is built.

Measured budget to meet before the default flips to on in `check!`: median added wall per test under 0.5 ms on a quiet machine, verified by the plugin's own timing field `:leak-check-ms` in the receipt (`:slowest`-style, LOOP-RECEIPT-LOAD-SLOWEST-006 pattern), so the cost is itself observable rather than asserted.

## False positives and false negatives

False positives (reported, not real hazards):

- A lazily started JVM/Swing singleton born inside the first test that touches it (allowlist; counted).
- The first `future` in the JVM creates a pool worker; the executor counters, not the thread birth, decide, so this is *not* reported unless the task is still in flight.
- A test deliberately starting a long-lived service that a `:once` fixture tears down later (`^:kaocha.leak/allow`).
- JIT compiler and GC threads: they are not `java.lang.Thread` objects and do not appear in either API (unverified for every JVM; the allowlist covers names if they do).
- Clock skew in `getTaskCount` on a busy pool (approximate counter): two reads, conservative direction; see Open Questions.

False negatives (real hazards not reported):

- Work handed to an executor the detector cannot enumerate whose threads are long-lived: a project's own fixed pool, `core.async` dispatch, `javax.swing.Timer`, `ScheduledExecutorService` delays. The thread exists in both snapshots and the queue is private. Mitigation in a later step: per-thread CPU time delta over the window (`ThreadMXBean.getThreadCpuTime`, supported here) flags baseline threads that *did* work during the test, which is a hint, not proof of work still pending.
- `invokeLater` posted to the EDT and not yet run (see Open Questions for `peekEvent`).
- Virtual threads (invisible to both APIs; verified).
- A leaked task that completes in the microseconds between the test returning and the post-test counter read. The window is as tight as the hook allows; the residual gap is the cost of not waiting.
- A test that leaks on one run and not another: detection is per run, which is why `repeat` mode carries the contamination label.

## Decisions and alternatives

| Decision | Chosen | Alternatives considered | Rationale |
|---|---|---|---|
| Hook point | `pre-test`/`post-test` on leaf testables | `wrap-run` (wraps groups too, mis-attributes to the ns); `:kaocha.testable/wrap` (no access to the result map); `:kaocha.hooks/post-test` fns in `tests.edn` via the `hooks` plugin (works for a project-local drain, but cannot ship a report format) | The capture-output precedent: store in pre-test, read in post-test, and the window includes `:each` fixtures so a drain fixture shows as the fix |
| In-flight signal for pooled work | Executor counters (`active + queued`) | Thread count; thread state | Workers idle for 60 s after a task; count and state cannot tell idle from working; the executor can |
| Signal for raw threads | Set difference of `threadId`s, allowlist, idle-worker signature | Count delta | Counts go down when earlier work finishes mid-test; identity gives exactly the births alive at exit |
| Settle window | None by default; `:grace-ms` opt-in, diagnostic only | 50–250 ms re-sample before reporting | In-flight at return *is* the violated invariant; a wait drains the leak and makes warm behave unlike cold |
| Verdict | Warn by default; `fail-on-leak` opt-in adds a Kaocha `:fail` event | Always fail; never fail | Tenet 1 protects verdicts, and the leaking test's verdict is sound; the at-risk verdict is the neighbour's, which is now annotated. Strict mode exists so a project can ratchet |
| Attribution | Forward: the next leaf inherits the in-flight record; a failing inheritor is marked contaminated | Backward only (report the leaker and stop) | The reader starts from the failure; the failure must name the suspect |
| Where it runs | Warm `check!` plugin list, and `tests.edn` for projects that want it cold | Warm only | A cold receipt with the same `:leaks` key lets the two be compared; tenet 2 keeps cold as the gate |
| Output | Additive receipt keys, stderr block, nothing on stdout | Modify `:failures` semantics; print to stdout | Tenet 3, and the JSON line is read by programs |
| Cost control | No stacks in pre-test; stacks only for new/active threads in post-test; `:leak-check-ms` recorded | Full `dumpAllThreads` both sides | Keep the per-test cost below what a reader can notice, and make the cost a receipt fact |
| Repairing leaks | Out of scope; SETUP §14 drain recipe | Plugin interrupts or joins leaked work | Interrupting app futures changes the code under test; joining is the settle window by another name |

## Tests (proposed names, for the registry `:tests`)

Clojure, against a fixture namespace under `fixtures/` that starts a `future` and returns:

- `:a-future-still-running-at-return-is-charged-to-its-test`
- `:a-drained-future-is-not-a-leak` (an `:each` fixture that waits on a counter; proves fixtures are inside the window)
- `:an-idle-pool-worker-left-by-an-earlier-test-is-not-a-leak`
- `:a-failing-test-that-inherited-in-flight-work-is-marked-contaminated`
- `:allowlisted-thread-births-are-counted-not-reported`
- `:fail-on-leak-adds-one-fail-event-and-nothing-else-changes`

Python (`bin/sublime-loop` rendering): `test_leaks_render_to_stderr_only`, `test_a_clean_check_has_no_leak_block`.

## Proposed registry intents

IDs continue after the highest `LOOP-*` number present in the working tree (`LOOP-PERF-MODE-008` is tagged in `bin/sublime-loop` and `probe.clj`; `registry.edn` currently ends at `LOOP-RELOAD-FAILURE-005`, so 006–008 are reserved by uncommitted work).

```edn
{:id :LOOP-LEAK-REPORT-009 :status :proposed
 :ears "When a leaf test returns while work it submitted during its run is still in flight (an Agent pool task active or queued, or a platform thread born during the test and alive at its return, excluding allowlisted names and idle pool workers), the check receipt shall carry a leaks entry naming that test var, each pool's submitted and in-flight counts, and each running thread's name and first non-library frame; entries beyond the limit shall be counted in leaks-omitted, and allowlisted or opted-out births shall be counted, never dropped. The readable block shall go to stderr, never stdout."
 :misreadings ["A new thread after a test is a leak." "A pool worker that stays alive is a leak." "Waiting a little before reading the counters gives a truer answer." "A count of zero leaks means no background work ran."]
 :boundaries ["Future still running at return" "Future drained by an :each fixture" "Idle send-off worker from an earlier test" "First-use AWT thread birth" "Opted-out test var" "More leaks than the limit" "Skipped, pending and load-error testables (no post-test hook)" "Virtual thread (not observable)"]
 :tests [:a-future-still-running-at-return-is-charged-to-its-test :a-drained-future-is-not-a-leak
         :an-idle-pool-worker-left-by-an-earlier-test-is-not-a-leak :allowlisted-thread-births-are-counted-not-reported
         :test_leaks_render_to_stderr_only :test_a_clean_check_has_no_leak_block]
 :rationale "ks-2u5, pdfreader 2026-10-31: RENDER-THREAD-001 returned while its logged-future was still displaying; the work landed in RENDER-THREAD-002 and every signal named the victim."}
{:id :LOOP-LEAK-VERDICT-010 :status :proposed
 :ears "A reported leak shall not change the leaking test's outcome, the check status, counts or exit code unless fail-on-leak is configured, in which case exactly one fail event shall be added to the leaking var. When the next leaf test starts while a previous test's in-flight work remains, that test shall be marked as inheriting it, and if it fails, its failure entry shall carry contaminated-by naming the earlier test."
 :misreadings ["A leak makes the leaking test's own verdict unprovable." "Strict mode may change counts of other tests." "The inheritor failed, so the inheritor is the bug."]
 :boundaries ["Default mode on a leaking green test" "fail-on-leak on a leaking green test" "Inheritor passes" "Inheritor fails" "Leak drained before the next test starts" "repeat mode flip with inheritance"]
 :tests [:a-failing-test-that-inherited-in-flight-work-is-marked-contaminated :fail-on-leak-adds-one-fail-event-and-nothing-else-changes]
 :rationale "Tenet 1 protects provable verdicts; the leaker's verdict is provable, the neighbour's is at risk. Naming the suspect beside the victim's failure is what ends the dig."}
```

## Open questions

1. **Approximate counters.** `ThreadPoolExecutor.getActiveCount`/`getTaskCount` are documented approximate. Is a double read with the conservative direction enough, or should in-flight be decided from `getQueue().size()` plus a per-thread "working" classification from stack signature only? Decide on the first false report.
2. **Swing EDT queue.** `Toolkit.getDefaultToolkit().getSystemEventQueue().peekEvent()` returning non-nil at post-test would catch `invokeLater` left pending. Unverified here; needs a probe on pdfreader, and a check that peeking from a non-EDT thread is safe.
3. **Per-thread CPU time** as a "baseline thread did work" hint: does it earn its cost, or does it mostly flag AWT painting?
4. **Default in `check!`.** Ship as opt-in (`test-focus --leaks`) for one field week, then default on once `:leak-check-ms` shows the budget is met?
5. **Cross-reference to `:slowest`.** A leaking test often looks fast (it returned early). Should the slowest/perf tables mark leaking tests so timings are not trusted?
6. **Where the plugin lives.** `src/kaocha/plugin/leak_detection.clj` beside `affected.clj` and `run_receipt.clj`, registered as `:kaocha.plugin/leak-detection`; confirm it must stay loadable without nREPL/tools.namespace so a project can use it cold.
