# Agent grammar v1 build evidence — 2026-09-08

The accepted implementation is source commit `a4da89b`. Subsequent commits add
harnesses, documentation and retained evidence only. `acceptance.json` indexes
the final results. Every owned Kaocha JVM used `-Xms64m -Xmx512m`.

## Red first and final gates

`red.log` precedes the implementation. `diff-schema-red.log`, `capture-red.log`,
`heap-stale-red.log`, `trigger-red.log`, and `equality-wrapper-red.log` preserve regression failures before
their fixes. The trigger regression was run against source from `2b94d97` in an
owned scratch directory. `unit-final.log` is 49 tests / 353 assertions, zero
failures/errors; `lint-final.log` is zero errors/warnings through `~/bin/clj-kondo`.
The normal suite enforces the bidirectional linked-intent registry.

`process-final.log` / `process/agent_witnesses.json` contain the final cold and
warm fixtures. Each clean/red follow timing has ten calls against a completed receipt. `watch-final.log` / `process/agent_watch_witness.json`
contain the real watcher cases. `mvr-acceptance.log` /
`process/agent_mvr_witness.json` contain the isolated MVR `9439370` full-suite run.
`legacy-process.log`, `legacy-cli.log`, and `legacy-cli/` retain the earlier
receipt/affected contracts' final process regressions at the required heap caps.

`watch-first.log`, `watch-recovery-first.log`, and `mvr-first.log` are exploratory
failures, **not accepted results**. They exposed loaded-byte/recovery/empty-namespace
coverage problems repaired before the final source freeze. Other intermediate
development logs remain outside the repo at
`/var/tmp/forge/kaocha-agent-v1-development-logs/`.

## Artifact interpretation

`process/*.events` are the actual owned streams, not examples. Their referenced
receipts are copied byte-for-byte beside them. The final retained DONE digests
were checked against those copies. `killed.events` has START and no DONE;
`killed-active.edn` preserves exact PID/start identity and the unfinished reservation.
The live files remain under `/var/tmp/forge/kaocha-agent-v1/`.

`process/*.log` are diagnostic output and **never protocol input**. Fixtures may
deliberately print forged prefixes. `*.diagnostic` are bounded capture artifacts.
The absent-cap admission witness injects an empty input-argument view into an
actually bounded JVM and proves refusal before test load; it does not launch an
uncapped JVM. The suite separately varies explicit/effective heap evidence.

## Reproduction and limits

Run `python3 scripts/agent_witnesses.py`, then
`python3 scripts/agent_watch_witness.py` with the source fixed for the whole run.
These recreate owned named fixture directories; retained copies here survive.
For MVR, set a fresh `AGENT_MVR_FIXTURE` name and run
`python3 scripts/agent_mvr_witness.py`. That harness uses local MVR checkout
`/home/forge/src/marvin-voice-remote` and the pre-existing classpath artifact
`/var/tmp/forge/kaocha-status-fx/classpath.txt`; the full actual command is retained
in its JSON trace. This is a Linux-local witness, not a portable provisioning tool.

Follow timings include a new Babashka status process each call, with an already
completed receipt. They are absolute observed walls, not edit-to-verified walls or
a performance comparison. The final process, watcher and MVR acceptance harnesses
ran concurrently on the shared host; these are not idle-machine latency claims.
MVR sampled RSS includes native/JVM overhead beyond the
512 MiB Java heap. No independent blind-reader or matched crossover was performed;
those remain Fable item 5. Adapter/launcher responsibilities are in
`docs/agent-v1.md` (Fable item 4 and batch 5).
