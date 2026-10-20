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
- Each check reloads the requested namespaces and their source dependencies (scanned
  in `src/` and `test/`), then runs exactly those namespaces through Kaocha.
- stdout is exactly one JSON receipt. Exit code: `0` pass, `1` fail/stale/tainted,
  `2` unknown.
- A failing receipt carries `failures`: the first three failing tests, each with `test`,
  `at` (`file:line`), `expr`, `expected`, `actual` and `context`. No need to open the
  `events` file for the common case.
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
| `dependencies, runner or aliases changed` | You edited `deps.edn`/`tests.edn`, pulled a library change, or passed different `--aliases`. |

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
| `unknown` | The loop itself failed (no JVM, lock timeout, startup failure). | Read `error`. |

Useful fields in a check receipt: `counts` (tests, assertions, pass, fail, error,
skipped-by-focus), `wall_s` (time for this check), `reload-targets` (namespaces
reloaded), `path` (Kaocha run receipt, EDN, with failure details), `generation`.

## 10. Troubleshooting

- **`runner busy for 120 seconds`** — another command holds `.dev-loop/lock` for this
  root. Wait for it to finish. Do not delete the lock while a check runs.
- **`test process tainted`** — `sublime-loop --root R down`, then start again.
  `test-focus` does this automatically.
- **`runner or dependencies changed`** — `deps.edn`, `tests.edn`, the library, or the
  aliases changed. `down` then `up`; `test-focus` does this automatically.
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

## Quick reference

```sh
KS=~/src.local/kaocha-sublime
$KS/bin/warm-jvm     --root R [--aliases :a:b]           # start (background)
$KS/bin/test-focus   --root R [--aliases :a:b] NS...      # focused check
$KS/bin/sublime-loop --root R [--aliases :a:b] status     # is it up?
$KS/bin/sublime-loop --root R down                        # stop
```
