# Agent grammar v1

The fixed prefix and ASCII JSON object form one record, terminated by LF. Only
runner-owned `<id>.events` and `kaocha-status` output carry this protocol. Human
output, captured test output, JVM diagnostics, and nREPL envelopes do not.

The grammar remains `v:1`: `parent_outcome` is an additive field on automatic
follow-up DONE records. Producers and strict readers must adopt this addition
together; older strict readers reject the new key as a protocol error.

```text
stream     := record LF ... ; UTF-8 transport, ASCII-only records, no ANSI/BOM/blank lines
record     := "RUN-START " J | "FAIL " J | "RUN-DONE " J | "STATUS " J
J          := one compact JSON object; keys below occur exactly once; key order irrelevant
common     := v:1, id:string, mode:(watch|probe|gate), candidate:(sha256|null)
RUN-START  := common + session:string, trigger:[string], trigger_omitted:uint,
              closure:(selected/total object|null), active:absolute-path
FAIL       := common + event:uint, kind:(fail|error|load), test:(string|null),
              file:(string|null), line:(positive-int|null), context:(string|null),
              expr:(string|null), expected:(string|null), actual:(string|null),
              diff:(string|null), truncated:[field-name], unavailable:[field-name]
RUN-DONE   := common + outcome:(pass|fail|error|incomplete), tests:(uint|null),
              assertions:(uint|null), pass:(uint|null), fail:(uint|null), error:(uint|null),
              pending:(uint|null), skipped:(uint|null), closure:(selected/total object|null),
              coverage:(complete|partial|unknown), wall_ms:uint, auto_followup:boolean,
              parent:(id|null), shown:uint, omitted:uint, receipt:absolute-path, sha256:hex64,
              parent_outcome:parent-result ONLY when auto_followup=true (required then)
parent-result := {"outcome":(pass|fail|error|incomplete|unknown),
                  "fail":(uint|null),"error":(uint|null)}
              unknown requires null counts; known outcomes require numeric counts;
              error>0 requires error; else fail>0 requires fail; else pass|incomplete
STATUS     := v:1, state:(marked|waiting|running|late|timeout|killed|superseded|uncovered|
              unavailable|refused), token:(string|null), id:(string|null),
              identity:(exact|unknown), elapsed_ms:(uint|null), phase:(queued|load|run|unknown),
              reason:enum, active:(absolute-path|null), receipt:(absolute-path|null)
enum       := fixed registered ASCII symbol; initial reasons: none, save-unobserved,
              over-p95, deadline, process-dead, candidate-changed, scope-missing,
              no-receipt, identity-unproved, heap-cap, protocol-error
string     := JSON-escaped; every control/non-ASCII code point escaped, including ESC/DEL
              and bidi separators; quotes/backslashes escaped; no literal caller newlines
null       := unknown/unavailable, never zero, never the text "nil" as missing evidence
limits     := START/DONE/STATUS ≤8192 bytes incl LF; FAIL ≤2048; run FAIL budget ≤16384
              incl LF and ≤20 events; field preview caps apply after encoding incl quotes
              truncate only previews on complete escape boundaries; list their field names
              trigger ≤4 paths/512 bytes, with exact remainder count; full set in receipt
              ids ≤200 ASCII-safe chars; tokens opaque ≤200; candidate/digest = 64 hex
              identity/path fields never truncated; oversize => STATUS refused/protocol-error
counts     := tests counts executed leaves; pass/fail/error count assertion events;
              assertions=pass+fail+error; pending/skipped separate, never counted as pass
closure    := {"selected":uint,"total":uint}; namespace groups, not test-Var counts
order      := one START before load, zero or more FAIL, at most one committed DONE per id
              replay may repeat records: deduplicate by id/type, and id/event for FAIL
completion := DONE is an immutable run result, not a statement of freshness for every caller
              error outranks fail; neither failure nor missing/pending coverage becomes pass
              auto_followup DONE also carries the parent's result and fail/error counts;
              its own outcome does not override parent_outcome or select the primary
accept     := follow-token exits 0 only for exact candidate + required coverage + pass;
              verified red exits 1; timeout/unknown/refused/incomplete exit 2
              terminal matched DONE is validated against the token by status/R12;
              direct log consumers must perform the same check before claiming “mine passed”
publication:= active reservation → START; final receipt rename → LATEST publication → DONE
              read atomic final receipt only; tmp is never completion; no DONE on kill/timeout
transport  := serialize records per destination; raw stdout/stderr cannot supply protocol data
compat     := PROBE/GATE adapters map to mode; no second summary; unknown version/key,
              malformed/oversize/conflicting record => protocol-error, never success
```

`parent_outcome` summarizes the immediate parent run from its committed receipt;
the child receipt retains the summary as `:parent-outcome` alongside the parent
ID and digest. A passing follow-up after a red primary therefore carries, for
example, `"parent_outcome":{"outcome":"fail","fail":1,"error":0}`. A passing
parent yields `pass` with zero fail/error counts. A missing or invalid parent
receipt yields `unknown` with null counts, never `pass`; older child receipts
without the persisted summary also project `unknown`. Replaying a completed
child uses its persisted summary, even if the parent receipt has since gone.
Primary DONE records omit this field. STATUS/FOLLOW selection and exit semantics
are unchanged: the primary token still selects the primary. This addition
implements [AGENT-PARENT-OUTCOME-019](intent/registry.edn), from blind-reader
witness case 4 (clj-surgeon-records `2035d62a`,
`docs/observations/2026-09-08-blind-reader-witness.md`).

A DONE with inconsistent arithmetic (`assertions != pass + fail + error`) is a
**protocol-error**, never pass and never merely an ordinary red test result.
Readers must validate the counts before interpreting `outcome`, even when the
receipt digest matches. Null assertion counts must be consistently null, not
mixed with numeric assertion counts. Reject contradictory outcomes as specified
by the grammar as well. The emitter validates its complete DONE projection
before publishing either the receipt or LATEST; invalid totals yield typed
refusal evidence without a committed result. This check does not depend on
Clojure's optional assertion setting.

## Implementation and acceptance

`kaocha-sublime.protocol` owns the shared encoder and DONE projection.
`kaocha.report/agent` is installed when `kaocha.plugin/run-receipt` loads; the
plugin is required with this reporter. Receipt schema 1 adds `:workspace`,
`:session`, `:generation`, `:classpath-generation`, `:candidate`, `:manifest`,
`:observed`, inode stamps and `:identity-proof`, `:identity`, `:mode`, `:heap`,
`:closure`, `:coverage`, executed/partial namespace lists, failure budget,
event/diagnostic paths and parent identity/digest. Ordinary receipts also record
requested and effective heap caps. The configured ceiling defaults to 512 MiB.

SESSION and tokens live beside the existing receipt/LATEST data. LATEST includes
the exact final receipt SHA256. Status validates the pointer, digest, schema,
workspace, session, generation, candidate manifest, and token-bound scope. It
projects the same immutable DONE used by the reporter; it does not read arbitrary
stdout or select final receipts by filesystem order. Active reservations take
precedence over completed data. Parent links are checked against a digest and
candidate identity before selecting the primary. Unknown or malformed evidence
never produces exit 0. An unchanged completed result can satisfy multiple marks
with the same content and scope, including marks made after completion.

`coverage=complete` describes the selected namespace closure. A full-scope token
additionally requires `closure.selected == closure.total`. Empty discovered
namespace groups contribute to namespace closure but invent no executed tests.
Pending tests do not execute, do not add assertions, and prevent a pass. The
declared gate defines complete coverage: leaves excluded by the effective
`:kaocha.filter/skip-meta` configuration do not reduce it. Receipts record
`:skip-rules` (declaring node ID and metadata keys), `:declared-skips` (excluded
leaf IDs and matching keys), and the separate `:totals :skipped` count. DONE's
`skipped` includes all unexecuted non-pending leaves, including nested skips;
only declared exclusions are exempt from coverage. Focused omissions, fail-fast
omissions, load errors, and pending tests remain incomplete. Zero executed tests
cannot pass even if every test was deliberately skipped.
A namespace scope requires every non-excluded test in those
namespaces; a Var-focused green cannot satisfy it. Exit 1 establishes an exact
candidate's failure/error; it does not establish complete coverage.

The manifest includes configured source/test/resource roots, additional affected
paths, configuration files, and directory entries on the classpath. Classpath
generation includes the canonical entries and hashes of classpath files.
The loaded image must have stable hashes, inode change times, and observed
watch generation across the run. Linux change-time support is required for exact
attestation. Unsupported observation stays unknown. Plain warm REPL entry has no
initial loaded-byte attestation; it still emits the same grammar in probe mode.
A config/classpath generation change needs a bounded image restart. Adapters
must invalidate after untracked Var-changing evaluation. Runtime-only coupling,
unreported external state, arbitrary in-process tampering, and another JVM's
mutations are not authenticated by this cooperative filesystem/run contract.

Previews use supported scalars and concrete Clojure collections only. Lazy seqs
and arbitrary objects get opaque type placeholders. A traversal is bounded by
128 nodes, depth 6 and a cooperative 10 ms budget. Escaping counts against each
field cap. Large equality maps get a small deterministic projection from
`clojure.data/diff` only when the inputs are proven finite and small; unsafe or
larger structures explicitly mark the diff unavailable. No custom printer,
assertion evaluation, colored diff renderer, or full stack appears inline.
Available previews and exact locations remain in the receipt even when the
inline event budget omits a FAIL. Captured output has separate 16 KiB/test and
64 KiB/run caps, with truncation recorded in the diagnostic metadata.

## Old PROBE/GATE adapter contract (Fable, item 4)

The old PROBE/GATE line becomes an adapter input with explicit `mode=probe` or
`mode=gate`; it must not produce a second summary beside RUN-DONE. The adapter
must carry the real run ID, receipt path/digest, candidate token, image/config
identity and required checks. It may emit DONE only after authenticating those
facts. Legacy text alone has identity unknown and cannot yield accepted green.
Non-Kaocha checks use null test counts. Existing cold-gate requirements remain
separate from any warm probe pass.

For `make test-probe`, nREPL envelopes, and coldstart-grade/R12: parse only the
owned protocol, reject duplicate/unknown JSON keys and versions, enforce byte
limits, and compare the referenced receipt against independently held token,
workspace, image/config and required-check expectations. Preserve required
coverage even when a primary or automatic follow-up is focused. Reject stale
candidates, digest mismatch, missing gates, incomplete evidence, and forged
stdout. Recover a committed matching receipt when output was lost. Map exits
0/1/2 without interpreting the old human `--legacy` exit as acceptance.

Launchers own bounded capture of process output that bypasses Kaocha capture,
and apply the canonical `-Xms64m -Xmx512m` launch caps. This repository implements
the plugin admission half; specimen launcher adoption remains batch 5's work.
After untracked nREPL eval that can alter project Vars, call
`kaocha-sublime.image/invalidate!` before requesting another probe/watch verdict.

## Independent reader witness (Fable, item 5)

Use the retained clean/fail/load-error/unsafe/interrupted/superseded records with
a blind reader. Ask whether it is running, whether it is this candidate, whether
the required checks passed, and where the failure is. Retain wrong verdicts and
extra receipt reads. Then perform the planned warm crossover with identical
checks/caps, counting absolute apparatus actions, model returns, emitted bytes,
and edit-to-verified wall. The current process witnesses do not substitute for
that independent reader or a matched performance control.
