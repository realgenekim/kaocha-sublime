# Adopting the warm test loop in any repo

This guide sets up the kaocha-sublime warm test loop in a Clojure repo: one owned,
test-only JVM per project root, per checkout, that runs named test namespaces in
seconds. It works in plain checkouts and in git worktrees, with no user-level config.

Three commands do the work, all in this library's `bin/`:

| Command | What it does |
|---|---|
| `warm-jvm` | Starts (or confirms) the warm JVM for a project root. Same as `sublime-loop up`. |
| `test-focus NS...` | Runs the named test namespaces in that JVM. Starts or restarts the JVM if needed, with a loud `COLD START` line. |
| `sublime-loop` | The engine: `up`, `status`, `check NS...`, `down`. |

## 1. Prerequisites

- **Clojure CLI** (`clojure` on `PATH`). Check: `clojure -Sdescribe`.
- **Java 17+**.
- **Python 3** (the `sublime-loop` script). Standard library only.
- **`lsof`** (proves which process owns the nREPL port). Present on macOS and most Linux.
- **`clj-nrepl-eval`** on `PATH`. The loop sends every request through it. Check:
  `which clj-nrepl-eval`. If it is missing, install it the way the `clojure-nrepl`
  skill describes; `sublime-loop up` refuses to start without it.
- A **project root**: a directory with `deps.edn` (and usually `tests.edn` for Kaocha).
  A monorepo can have several roots; each gets its own JVM.

The project does **not** need Kaocha, nREPL or tools.namespace in its own `deps.edn`.
The library brings them.

## 2. Where the library lives

| Machine | Path |
|---|---|
| Laptop (skiff) | `~/src.local/kaocha-sublime` |
| Buster / anvil | `/home/forge/src/kaocha-sublime` |

Call the scripts by absolute path, or put `bin/` on your `PATH`:

```sh
KS=~/src.local/kaocha-sublime            # or /home/forge/src/kaocha-sublime
$KS/bin/warm-jvm --root .

# optional, in your shell profile
export PATH="$HOME/src.local/kaocha-sublime/bin:$PATH"
```

The JVM always loads the library from the checkout that holds the script you ran.
`sublime-loop` adds it to the JVM itself (see section 4), so you never write the
library's path into a repo.

## 3. Choose the aliases for each project root

The runner JVM is started as:

```text
clojure -Sdeps '<library alias>' -M<your aliases>:kaocha-sublime/library
```

with the project root as the working directory. `<your aliases>` must put on the
classpath everything your tests need: the `test` directory, test-only deps (ring-mock,
test.check, ...), and any JVM flags the tests expect (for example
`-Dguardrails.enabled=true`).

The fastest way to choose: copy the aliases your existing test command uses.
If `bin/kaocha` says `clojure -M:run-tests:dev:test`, use `--aliases :run-tests:dev:test`.
The `:main-opts` of those aliases do not matter; the library alias comes last and its
main wins.

Default when you pass no `--aliases`: `:test`, plus `:sublime` if the repo's
`deps.edn` defines a `:sublime` alias.

**Always pass the same `--aliases` for a root.** If `check` (or `test-focus`) is given
different aliases than the running JVM was started with, it refuses with
`runner or dependencies changed`, and `test-focus` restarts the JVM (a COLD START).

### Optional: a repo `:sublime` alias

Add one only when the tests need extras that no existing alias provides, for example
a `dev` source path. Copy and adjust:

```clojure
;; deps.edn, under :aliases
;; Extras for the kaocha-sublime warm test JVM. The library itself is injected by
;; sublime-loop; do not name its path here.
:sublime {:extra-paths ["dev"]
          :extra-deps {ring/ring-mock {:mvn/version "0.4.0"}}
          :jvm-opts ["-Dguardrails.enabled=true"]}
```

Then the default aliases (`:test:sublime`) pick it up, and you can omit `--aliases`.

### Examples

| Root | Aliases | Why |
|---|---|---|
| A small library | (none; default `:test`) | `:test` adds `test/` and test deps. |
| A monorepo app root such as `server2` | `--aliases :run-tests:dev:test` | Its test deps live in `:run-tests`, dev paths in `:dev`. |
| A root whose tests need `-D` flags | `--aliases :test:run-tests` | `:run-tests` carries the `:jvm-opts`. |

### Migrating an older `:sublime` alias

Older adopters have an alias such as:

```clojure
:sublime {:extra-paths ["dev"]
          :extra-deps {local/kaocha-sublime {:local/root "../kaocha-sublime"}
                       nrepl/nrepl {:mvn/version "1.3.1"}
                       org.clojure/tools.namespace {:mvn/version "1.5.0"}}
          :main-opts ["-m" "feedback"]}
```

It keeps working: the injected alias comes last, so its `local/kaocha-sublime`
coordinate (an absolute path) replaces the relative one, and its main replaces
`-m feedback`. A relative `:local/root` breaks in worktrees, which live at
`~/src.local/worktrees/<repo>/<name>/`, so remove it when you next edit the alias.
Keep only the project extras (`:extra-paths`, extra test deps, `:jvm-opts`).

## 4. How the library gets on the classpath

`sublime-loop` passes this to `clojure -Sdeps` (paths and versions come from the
script itself):

```clojure
{:aliases {:kaocha-sublime/library
           {:extra-deps {local/kaocha-sublime {:local/root "<absolute library path>"}
                         nrepl/nrepl {:mvn/version "1.3.1"}
                         org.clojure/tools.namespace {:mvn/version "1.5.0"}}
            :main-opts ["-m" "kaocha-sublime.runner"]}}}
```

The library's own `deps.edn` adds Clojure 1.12 and Kaocha 1.91.1392.

The path is the library checkout that holds the running script. The script fingerprints
that same checkout (`deps.edn`, `bin/sublime-loop`, `src/**/*.clj`). When any of those
files change, the next `check` refuses with `runner or dependencies changed`, and
`test-focus` restarts the JVM. The JVM therefore always runs the library code that the
receipt names.

## 5. Ignore the state directory

All state lives in `<root>/.dev-loop/` (session record, lock, receipts, boot log,
Kaocha run receipts). Add it to `.gitignore`. One line at the repo root covers every
project root below it:

```gitignore
# kaocha-sublime warm test JVM state (per project root)
.dev-loop/
```

## 6. First run

Start the JVM in the background at the beginning of a session, in the checkout you
are editing:

```sh
$KS/bin/warm-jvm --root server2 --aliases :run-tests:dev:test
```

It prints one JSON receipt and exits:

```json
{"root": "/abs/path/server2", "pid": 8792, "generation": "e793...", "role": "test-only",
 "port": 54660, "environment": "a9b6...", "aliases": ":run-tests:dev:test",
 "status": "ready", "schema": "dev-loop.v1", "wall_s": 6.08, "queue_s": 0.0,
 "wrapper_receipt": "/abs/path/server2/.dev-loop/result-<uuid>.json"}
```

- `status: ready` means the JVM is up and proved its identity over nREPL.
- `root` is the canonical project root. Check that it is the checkout you are editing.
- `generation` identifies this JVM. Every later receipt carries it.
- `environment` is the fingerprint of the repo's `deps.edn`/`tests.edn` and the library.
- Startup takes a few seconds once deps are in `~/.m2`. The first run in a new repo
  may download deps first.

`warm-jvm` is idempotent: a second call reuses the live JVM.

## 7. Focused checks

```sh
$KS/bin/test-focus --root server2 --aliases :run-tests:dev:test \
  server2.handlers.discover-star-tour-media-test
```

- Name test namespaces explicitly. Several are allowed, space- or comma-separated.
- Name one test var as `ns/var` to run only that test, alone or mixed with namespaces:
  `test-focus my.app.core-test/adds-two my.app.db-test`. The var's namespace (and its
  source dependencies) reload exactly as if you had named the namespace. A name that is
  not a test var is refused by name (`status: fail`, `stage: focus`,
  `error: "no such test var: ..."`) before anything runs, and costs no restart.
- Each check reloads the requested namespaces and their source dependencies (scanned
  in `src/` and `test/`), then runs exactly the named namespaces and vars through Kaocha.
- stdout is exactly one JSON receipt. Exit code: `0` pass, `1` fail/stale/tainted,
  `2` unknown.
- A failing receipt carries `failures`: one entry per failing or erroring assertion, in
  run order, at most 10 (the rest are counted in `failures-omitted`). Blank fields are
  left out:

  | Field | Content |
  |---|---|
  | `kind` | `fail`, `error` (uncaught exception) or `load` |
  | `test` | the test var, `ns/name` |
  | `at`, `file`, `line` | `file:line` relative to the root, and its parts |
  | `message` | the assertion message (`(is ... "msg")`), or Kaocha's reason (for example a test with no assertions) |
  | `context` | the `testing` strings, outer to inner, joined with ` > ` |
  | `expr` | the asserted form |
  | `expected`, `actual` | the values as `pr-str`, cut at 600 characters (an `=` assertion shows the two evaluated sides; an error shows class, message, `ex-data`, causes and the first frame in your code) |
  | `output` | the test's captured stdout and stderr, last 2 KB |

  ```json
  {"kind": "fail", "test": "demo.core-test/adds-wrong",
   "at": "test/demo/core_test.clj:12", "file": "test/demo/core_test.clj", "line": 12,
   "message": "maps should match", "context": "small numbers",
   "expr": "(= {:a 1, :b [1 2 3]} {:a 1, :b [1 2 4]})",
   "expected": "{:a 1, :b [1 2 3]}", "actual": "{:a 1, :b [1 2 4]}",
   "output": "timing: 42 ms\nwarn: on stderr\n"}
  ```

  `println` in a test is a fine way to see a value: it arrives in `output`.
- After the JSON line, a failed check prints a readable block on stderr, one stanza per
  failure, so a plain terminal run needs no `jq`:

  ```text
  test-focus: FAIL -- 4 tests, 1 fail, 1 error

  FAIL demo.core-test/adds-wrong  test/demo/core_test.clj:12
    message:  maps should match
    context:  small numbers
    expr:     (= {:a 1, :b [1 2 3]} {:a 1, :b [1 2 4]})
    expected: {:a 1, :b [1 2 3]}
    actual:   {:a 1, :b [1 2 4]}
    output:   (last 2 of 2 lines)
      | timing: 42 ms
      | warn: on stderr
  ```

  Parse stdout; never parse the stderr block.
- `--root` resolves the same from anywhere in the checkout: as given (against the working
  directory), else against the git toplevel. `--root server2` works from the top, from
  inside `server2/` and from a sibling. A root that resolves nowhere is refused with exit 2,
  the paths tried, and no JVM started (never a COLD START).
- The first check in a fresh JVM loads Kaocha and your code (10-20 s). Later checks
  take seconds.

The red-green loop: write the failing test, `test-focus` (expect `fail`, exit 1),
make it pass, `test-focus` (expect `pass`, exit 0).

A focused check is an inner-loop signal, not a gate. Every receipt says
`verification_complete: false`. Run the repo's full suite before you call work done.

## 8. COLD START

`test-focus` prints this on stderr when it must boot or restart the JVM:

```text
test-focus: COLD START -- no warm JVM for this root; starting the warm JVM (sublime-loop up)
```

| Reason | Expected when |
|---|---|
| `no warm JVM for this root` | First check of a session, or after `down`. Once per session is normal. |
| `warm JVM tainted` | A previous check did not finish cleanly. |
| `dependencies, runner or aliases changed` | You changed what `deps.edn`/`tests.edn` say about the runner (`:deps`, `:paths`, another top-level key, or a used alias), pulled a library change, or passed different `--aliases`. Comments, whitespace, key order and unused aliases do not count. |

Two things are never a COLD START:

- **UNVERIFIED** — the warm JVM is alive and owns its port but did not answer nREPL in
  time, which happens on a heavily loaded machine. `test-focus` prints
  `test-focus: UNVERIFIED -- ...`, prints the `unverified` receipt and exits 2. It does not
  start a second JVM. Retry when the load drops.
- **A reload error** (a syntax error, an unresolved symbol) — the check fails at
  `stage: reload` and the JVM stays warm. Fix the file and run the check again.

More than one COLD START per session, without one of these causes, means the loop is
paying JVM startup on every check. Find out why.

## 9. Reading receipts

Every command prints one `dev-loop.v1` JSON receipt and also writes it to
`.dev-loop/result-<uuid>.json` and `.dev-loop/last-result.json`.

| `status` | Meaning | What to do |
|---|---|---|
| `ready` | JVM is up and attested. | Run checks. |
| `pass` | Every requested test ran and passed. | Continue. |
| `fail` | A test failed, or reload/compile failed (`stage` says which: `dependency-scan`, `reload`, `probe`). | Read `error` and the Kaocha receipt at `path`. |
| `stale` | Source files changed while the check ran. | Run the check again. |
| `tainted` | A check did not reach a terminal result; the JVM may be half-loaded. | `sublime-loop down`, then `warm-jvm` (or just run `test-focus`). |
| `stopped` | `down` worked, or a stale session record was archived. | — |
| `unverified` | The JVM process is alive and owns its port, but no nREPL answer arrived in time (machine under load). Not ready, not gone. | Retry later. `evidence` has the pid, port, attempts and load. To replace it: `down`, then `up`. |
| `unknown` | The loop itself failed (no JVM, lock timeout, startup failure). | Read `error`. |

Useful fields in a check receipt: `counts` (tests, assertions, pass, fail, error,
skipped-by-focus), `failures` and `failures-omitted` (see section 7), `wall_s` (time for
this check), `reload-targets` (namespaces reloaded), `path` (Kaocha run receipt, EDN),
`checks` (the targets you named), `generation`. A `fail` with `stage: focus` means a named
test var does not exist; the JVM stays warm.

A `fail` with `stage: reload` means a namespace did not load. Nothing ran, and the check
is never a `pass`. The receipt carries:

| Field | Content |
|---|---|
| `reload-failure` | `namespace`, `at` (`file:line`), `file`, `line`, `column`, `phase` (`read-source`, `compile-syntax-check`, `macroexpansion`, `execution`), `message`, `class` |
| `failures` | one entry with `kind: load`, `test` = the namespace, `at`, `message` (so the stderr block shows it) |
| `not-reloaded` | the failed namespace and the ones after it: they keep their previous code until the next check |
| `jvm-usable` | `true` unless the error says the JVM itself is in trouble (for example `OutOfMemoryError`); only then is the runner tainted |

An `unverified` receipt carries `evidence`: `pid`, `port`, `generation`, `attempts` (each
with `elapsed_s` and `detail`), `budget` (`timeout_s`, `attempts`, `backoff_s`), `load_1m`,
`cpus` and `per_core`. The probe waits 5 s times (1 + load per core), at most 20 s, for at
most 3 attempts.

## 10. Troubleshooting

- **`runner busy for 120 seconds`** — another command holds `.dev-loop/lock` for this
  root. Wait for it to finish. Do not delete the lock while a check runs.
- **`test process tainted`** — `sublime-loop --root R down`, then start again.
  `test-focus` does this automatically.
- **`runner or dependencies changed`** — what `deps.edn`/`tests.edn` say about the runner,
  the library, or the aliases changed. `down` then `up`; `test-focus` does this
  automatically. A comment or whitespace edit does not cause it (a `deps.edn` that does not
  read as EDN is compared byte for byte).
- **`status: unverified`** — see section 9. The JVM is probably busy, not dead. Do not
  start another one; retry, or `down` then `up` if it stays unresponsive when the machine
  is quiet.
- **Stale session record** — if the JVM died, the next command archives
  `session.json` to `session.stale-<pid>-<UTC>.json` and reports `stale_record`.
  `up` then starts fresh. A process that is not proven to be this loop's JVM is never
  signalled.
- **`startup failed` / `startup exceeded 90 seconds`** — read `<root>/.dev-loop/boot.log`.
  Common causes: an alias that does not exist, a dep that does not resolve (check with
  `clojure -Spath -M<aliases>` in the root), a `:local/root` that does not exist in this
  checkout, or a slow first deps download (run `clojure -P -M<aliases>` once, then retry).
- **`install clj-nrepl-eval`** — see Prerequisites.
- **A test passes in the full suite but fails in a focused check** (or the reverse) —
  the test probably depends on another namespace's side effects or on JVM flags. Check
  that `--aliases` matches the suite's command.

## 11. Worktrees

Each worktree gets its own JVM. Run `warm-jvm` inside the worktree you are editing.

Why: the JVM reloads source from its own project root. A JVM started in another
checkout runs that checkout's code, so its green says nothing about your edits. The
session record stores the canonical `root`, and every request checks it.

Worktrees work without setup because no repo alias names the library's path. A
worktree's own relative `:local/root` deps (for example `../postgres` inside a
monorepo) still resolve, because they point inside the same checkout.

## 12. Stopping

```sh
$KS/bin/sublime-loop --root server2 down
```

Stop the JVM at the end of a session and before you delete a worktree. Each JVM uses
up to 512 MB of heap.

## 13. Delegating to agents

When a coordinating session (the "main loop") hands test-backed work to agents, the warm JVM is shared infrastructure. These rules keep a batch to one COLD START per root. Repo-specific details (roots, aliases, gate commands) belong in each repo's own agent brief, which should point here rather than copy this section.

1. **The coordinator owns the warm JVM.** Before dispatch it pulls kaocha-sublime and runs `warm-jvm --root <root> --aliases <aliases>` (inside the agent's worktree when there is one; see section 11), and names the root and aliases in each agent's prompt. That is the one COLD START per root.
2. **Agents run only `test-focus`.** Never `warm-jvm`, `sublime-loop up`/`down`, `bin/kaocha`, or a cold `clojure -M:test`.
3. **Write each new file in one complete save** (ns form, requires, first test), and create all new files before the first check. A half-written namespace fails at `stage: reload`; fix it and rerun. It is not a reason to restart.
4. **`UNVERIFIED` is not permission to restart.** Wait and rerun `test-focus`.
5. **No dependency, path or alias change mid-batch.** Such a change correctly forces a COLD START. An agent that needs one stops and reports it; the coordinator makes the change before the next batch.
6. **Never upgrade kaocha-sublime itself while agents are running.** The fingerprint covers the runner, so a runner change restarts every warm JVM on the machine that uses it on its next check, in every repo. Pull upgrades between batches, and tell other active seats on the same machine.
7. **`startup exceeded 90 seconds` or any `unknown` status:** read `<root>/.dev-loop/boot.log`, report it, and stop testing.
8. **Every agent reports each COLD START with its receipt `error` text** (`grep -h '"error"' <root>/.dev-loop/result-*.json | sort | uniq -c`). The agent's budget is zero; the coordinator's is one per root.
9. **Per-var focus (`ns/var`) while iterating;** name the namespaces for the final check. The full-suite gate belongs to the coordinator (sequential, in the background) or CI, never to an agent.
10. **The coordinator runs `sublime-loop down` at the end** and confirms the PID is gone.

## 14. Pitfalls and recipes

Field-tested on a Clojure/Swing app over one day: about 15 agents, one warm JVM, a laptop often at load 40-120. Each item is a problem that cost real time, with the fix to copy.

### Test-only JVM flags belong in the alias the warm JVM uses

If tests touch user state (a session file, a log under `$HOME`), redirect it with a JVM property set in the test alias, and add a test that fails if the test JVM points at the real path. Tests that drive the app's real load path will otherwise overwrite the user's files.

```clojure
;; deps.edn
:test {:extra-paths ["test"]
       :jvm-opts ["-Dmyapp.state-file=/tmp/myapp-test-state.edn"]}
```

### Background work must not outlive its test

A test that waits until a stubbed render was *called* can end while the future that called it is still finishing. Under load the leftover work lands inside the next test, which fails on code it never touched. Resetting app state between tests does not help if it also resets the counter the leftover work checks. Drain in-flight work in an `:each` fixture, and wait on conditions with long deadlines instead of sleeping:

```clojure
(defn- drain-background-work []
  (let [deadline (+ (System/currentTimeMillis) 30000)]
    (while (and (pos? @pending-work) (< (System/currentTimeMillis) deadline))
      (Thread/sleep 10))))

(use-fixtures :each (fn [f] (reset-state!) (f) (drain-background-work)))
```

A 30 s deadline costs nothing when the condition holds quickly; a 2 s deadline fails on a busy machine.

### After moving functions between namespaces, call through the alias

`(:require [my.render :refer [render-page]])` in a warm JVM keeps the caller bound to the OLD var if the caller's namespace already defined that name. Tests pass warm on stale code and fail cold. Use alias-qualified calls (`render/render-page`) after any move, and point `with-redefs` at the new var. The cold full suite is what catches this; keep it as the commit gate.

### Split wall-clock tests out of the commit gate

Tests that assert a time budget pass and fail on identical code depending on load. Tag them and give them their own suite:

```clojure
;; tests.edn
{:tests [{:id :unit :test-paths ["test"] :ns-patterns ["-test$"] :skip-meta [:perf]}
         {:id :perf :test-paths ["test"] :ns-patterns ["-test$"] :focus-meta [:perf]}]
 :fail-fast? true}
```

```make
runtests-once:   # the commit gate; output saved, kaocha's exit code kept
	@bash -o pipefail -c 'bin/kaocha --fail-fast unit 2>&1 | tee TEST-OUTPUT.txt'
perftests:       # run on a quiet machine; every timing reported
	@echo "Load: $$(uptime | sed 's/.*averages: //')"
	@bash -o pipefail -c 'bin/kaocha --no-fail-fast perf 2>&1 | tee TEST-OUTPUT-perf.txt'
```

`:skip-meta` is honored by warm `test-focus` runs as well. A global `:fail-fast? true` stops the perf suite at its first slow test, so pass `--no-fail-fast` there. For a timing test itself: warm up at the measured size first, take the best of 3 runs, and never read a timing taken above ~1.5 load per core.

### Keep the cold full suite, in the background, with its output saved

The warm loop is the inner loop; a cold `make runtests-once` before each commit is the gate. Run it in the background and tee it to a file: an unsaved failure from a flaky run cannot be diagnosed afterwards.

### Shell quoting

In zsh, `test-focus $NS` with `NS="a-test b-test"` passes ONE argument and the check is refused. Spell namespaces out, or use `${=NS}`.

### Reading timings

Note the load average beside every timing you report. On a laptop with large swap use, an idle JVM is paged out and its next check can take many times longer. That measures the machine, not the code.

## Quick reference

```sh
KS=~/src.local/kaocha-sublime
$KS/bin/warm-jvm     --root R [--aliases :a:b]           # start (background)
$KS/bin/test-focus   --root R [--aliases :a:b] NS|NS/VAR... # focused check
$KS/bin/sublime-loop --root R [--aliases :a:b] status     # is it up?
$KS/bin/sublime-loop --root R down                        # stop
```
