# Watch status witness, 2026-09-08

The accepted real-watcher sequence is `status-watch/witnesses.jsonl`. Its harness
is `../scripts/status_watch_witness.py`: every verdict is discovered through the
one-line executable; stdout from Kaocha is retained only as diagnostic evidence.
All DONE totals are checked against the exact named EDN receipt. No newest-file
heuristic, log tail, mocked watcher, or synthetic queue event is used by this harness.

Specimen: detached `marvin-voice-remote` `nrepl/test-alias`, commit `9439370`, at
`/var/tmp/forge/kaocha-status-fx/mvr`. The classpath was resolved with:

```bash
clojure -Sdeps '{:deps {local/kaocha-sublime {:local/root "/home/forge/src/kaocha-sublime"}}}' \
  -Spath -A:run-tests > /var/tmp/forge/kaocha-status-fx/classpath.txt
```

The harness launches one Java process using that classpath, the alias's
`guardrails.enabled=true` and `mvr.data.dir=target/test-data` properties,
`STORE_BACKEND=jsonl`, and the actual `kaocha.runner --watch` with both plugins.
The complete argv is in the launch row. No nREPL/server listener is launched.
The accepted JVM PID was **2902707**; the kill test writes its own PID to a
ready file, which must equal the launched process before exact-PID SIGKILL.
The harness waits for that PID to exit, invokes the explicit reaper, then restores
the specimen file. The worktree is clean and the JVM is gone.

The first full run had 579 tests / 7833 passing assertions. The in-place mutation
changes one `auth-test` bogus-cookie assertion from `nil?` to `some?`; restoring
it produces focused green and Kaocha's automatic full-rerun branch. The existing
affected plugin keeps the same 1/54 namespace selection for that unchanged
follow-up, so both green receipts have 6 tests / 35 passing assertions. The
follow-up is a separate run with `:auto-followup? true`, empty trigger files,
and an explicit `:followup-of` pointing to the save's green receipt.

`sed -i` appends a comment. Both the immediate check and another after 1.5 seconds
are STALE, and LATEST remains unchanged. The crash fixture inserts a 60-second
sleep into an existing test without adding assertions; the JVM is killed while
that body is blocked. An additional in-place edit during that run proves STALE
against the earlier start, before the kill.

Timings are Python `perf_counter_ns` wall time around ten separate complete
`bin/kaocha-status --dir ...` subprocesses, including Bash, Babashka startup, file
reads and process exit. They are not in-process function timings. Babashka
1.13.219: **23.854421 ms median; 25.567515 ms maximum**, all ten below 200 ms.
These are warm filesystem-cache calls while the real watcher is alive and idle;
they are not a claim about cold disk caches or another operating system.

Two unsuccessful real attempts are retained under `status-watch-attempts/`:

- Attempt 1: Beholder supplied `java.nio.file.Path`, which the initial fixture
  (using `java.io.File`) missed. The adapter threw before accepting the save.
  `status-path-red.log` records the added Path regression failing before the fix.
- Attempt 2: immediate status was STALE because reservation happened only after
  rescan/configuration. `status-rescan-red.log` records the failing boundary test.
  Reservation now starts when the watcher accepts a rescan, before that work.

Each real attempt used a fresh detached worktree at the same canonical fixture
path. The failed worktrees and their original raw artifacts are preserved under
`/var/tmp/forge/kaocha-status-fx/attempt1/` and `attempt2/`. The accepted attempt's
source bytes are those in implementation commit `51d8866`; subsequent changes
add regression tests and documentation only.

Initial red-first suite: `status-red.log` (8 failures / 31 errors, including the
then-absent CLI). Final suite: `status-green.log` (25 tests / 203 assertions,
zero failures/errors). Five append-only linked intents have code and test tags,
enforced bidirectionally in the normal suite. Native lint through
`~/bin/clj-kondo` reports zero errors/warnings (`status-lint.log`).
Existing subprocess and CLI witnesses pass (`status-process.log`,
`status-cli.log` and `status-cli/`); retained prior specimen evidence still verifies
(`status-prior-evidence.log`).
