# Measurement method and retained attempts

Baseline is the supplied 2026-09-08 TSV; no baseline value is reconstructed from
headline prose. Ten observations per color/specimen, median and nearest-rank p95
(no trimming). With n=10, p95 is the maximum. Baseline mvr red iteration 9 has
13 failures; green iteration 9 is 5.867 seconds. Both remain in the denominator.

The script is the supplied watchbench.sh: same in-place, fsynced edit, timestamp
after save, per-line Perl HiRes stamps, reporter-summary verdict detection,
50ms polling, one-second red/green pauses. Adaptations: owned output paths;
plugin launch using local/root; stdin /dev/null; record exact JVM/stamper PIDs.
No ports or shared REPL were used. The source/test files are the same baseline
blob_test.clj and version_test.clj, with the same assertions inverted.
Final mvr uses its original bin/kaocha JVM settings; ccfp uses original Xmx512m.
STORE_BACKEND=jsonl and CFP_MAIL_DISABLE=1 keep the test backend faithful.

The original lost implementation is in watch-v0 (full auto-follow-up after green).
A preliminary mvr run used Xmx512m; its full-cost resource results are retained
under mvr-cap512 and excluded from the final comparison. The final mvr run uses
unique log name mvr-final; no preliminary observations are pooled into it.
During restarting that preliminary run, an attempted parent cleanup encountered
an orphan's PID 1 and was refused by the OS; no PID 1 signal was delivered.
Remaining owned harness processes were then enumerated and stopped by exact PID.
The final run began only after all old mvr JVM/harness/stamper processes stopped.
Cleanup now checks only recorded executable+worktree PIDs (stop_watcher.py).

Root deps.edn edits do not emit a watch event with these specimen configurations.
One negative probe was interrupted and restored after ~2 minutes without a run.
The explicit --affected-since CLI proves deps.edn fallback. Watch fallback timing
uses an existing CSS resource under the watched roots; it changes and restores
in place. Neither a fabricated event nor Enter is attributed to a deps save.

These are sequential historical comparisons on a shared host, not simultaneous
paired controls. Host load and JVM history vary. Save→verdict includes watcher,
snapshot, reload, planning and execution. Receipt wall-ms starts at the receipt
pre-load hook, after Kaocha's internal watch reload; it is not save→verdict.
The baseline repeatedly queues red saves behind a full automatic green follow-up;
the plugin's one-use follow-up is small. The captain's 6.8s / ~64s idle-full
estimates therefore differ from the raw 12.46s / 127.68s medians.

Ten-iteration timing code: c72bc3c. Receipt EDN hardening at 4fcb459 was then
validated with local tests, both full specimen counts, and ccfp's final
resource watch fallback. The selection algorithm did not change after timing. Final local suite: 13 tests /
44 assertions; the final hash-printer fix changes only canonical EDN spelling.
Reported namespace counts are Kaocha namespace groups; these specimens scan src
as well as test, so groups can contain zero test vars. Executed test counts are
reported separately. Identity sets are 149 total = changed ns + 148 dependents.

Final ccfp resource probe initially refused before editing because the full CSS
exceeded argv limits. watch_witness.py now sends a unique changed span to the
unchanged in-place helper. Final successful change/restore timings are retained.
Both specimen worktrees were clean before removal; no JVM remained in either.
Worktrees removed after receipt and log verification and artifact copying.
