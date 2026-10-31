---
parent: high-level-design
topic: stale-refer
bead: ks-2gv
intents: [LOOP-STALE-REFER-011, LOOP-STALE-REPAIR-012]
status: proposed
---

# Stale refers: a warm image whose mappings no longer match the files

## Problem

A warm JVM reloads namespaces with `(require ns :reload)`, which re-evaluates a file but never removes what the file no longer says. After a function moves from one namespace to another, the old namespace can keep its old var, callers keep calling the old body, and tests pass against code the files no longer contain. The cold gate then fails.

Field case (pdfreader, 2026-10-31): clj-surgeon moved functions from `pdfreader.core` to `pdfreader.render` and gave `pdfreader.core` a `(:require [pdfreader.render :refer [...]])` for the moved names. In the warm JVM, `pdfreader.core` already interned vars of those names. Clojure refused the refer, kept the old vars, and `with-redefs` on the new `pdfreader.render` vars never reached the code the test exercised. The suite passed warm and failed cold. The SETUP §14 recipe ("call through the alias after a move") is a workaround for callers; this design makes the condition visible, and repairable, at the check that reloaded.

## Mechanics (verified on Clojure 1.12.0 / OpenJDK 27, probe scripts run 2026-10-31 in this repo's classpath)

**The runner's reload path.** `kaocha-sublime.runner/check!` scans with `clojure.tools.namespace.dir/scan-dirs`, takes `::track/load` (topologically sorted, dependencies first: `track.cljc` `add` sorts `changed` with `dep/topo-comparator`), filters it to the requested namespaces' transitive dependency closure, and calls `(require target :reload)` on each in that order. Nothing in `src/` calls `remove-ns` or `ns-unmap` on project namespaces (the one `ns-unmap` in `agent.clj` is the runner's own reporter var). So every interned var survives a reload whether or not the file still defines it.

**Refer over an interned name of the same namespace is refused and kept.** Re-evaluating `(ns probe.b (:require [probe.a :refer [foo]]))` when `probe.b` already interns `foo` prints

```
REJECTED: attempt to replace interned var #'probe.b/foo with #'probe.a/foo in probe.b, you must ns-unmap first
```

to `*err*` and leaves `probe.b` mapping `foo` to `#'probe.b/foo` (`ns-interns` still has it, `ns-refers` does not). The bytecode of `clojure.lang.Namespace` carries the string in `intern`/`reference` (`javap`, no sources jar on this machine). The message is written through `*err*`: binding `*err*` to a `StringWriter` around the `require` captures it. In the warm runner, `*err*` during reload is the JVM's own stderr, which goes to the runner log that nobody reads during a check. This is the exact field case.

**Refer where the home namespace *replaced* the var** (`ns-unmap` then `defn`): the referrer's re-evaluation prints `WARNING: g already refers to: #'probe.h/g in namespace: probe.r, being replaced by: #'probe.h/g` and *does* replace the mapping. Only reached when something unmaps, which the runner never does. A different-home replacement (the `IllegalStateException` "already refers to" path in the bytecode) was not exercised: unverified.

**Refer where the home namespace no longer has the var:** `java.lang.IllegalAccessError: gone does not exist` from `clojure.core/refer`, a loud reload failure that LOOP-RELOAD-FAILURE-005 already reports. In the warm runner this fires only when the home namespace lost the var, which, without unmapping, never happens warm. It is what the cold JVM says. The asymmetry is the whole problem: cold is loud, warm is silent.

**A referrer not in the reload closure is not touched at all.** If `probe.c` refers `foo` from `probe.b` and only `probe.b`'s closure reloads, `probe.c` keeps mapping `foo` to whatever var object it had. `(.ns var)` and `(.sym var)` on that var name its home and original name, so the staleness is checkable from the referrer's side.

**Redefinition wipes var metadata added at runtime.** After `(alter-meta! v assoc ::gen 1)` on every interned var of a namespace, re-evaluating `defn`, `def`, `declare`, `defonce` and `defmulti` all cleared the tag (the compiler's `def` always sets metadata, with `:file` and `:line`), while an untouched var kept it. `defonce` kept its *value* and lost the tag. `(intern ns sym val)` with no metadata on the symbol keeps the tag and leaves `:file` nil. This gives a cheap, exact test for "this var was not defined by the file the last reload evaluated".

**`ns-map`/`ns-refers`/`ns-publics` shapes.** `ns-map` holds Vars and Classes (681 Vars, 96 Classes for a small namespace); `ns-refers` in 1.12 *includes* `clojure.core` refers, so a sweep must filter `(= (.ns v) (the-ns 'clojure.core))` out. `clojure.tools.namespace.parse/read-ns-decl` returns the `ns` form; `deps-from-ns-decl` returns the lib set but not the `:refer` lists, so extracting declared refers is this design's own code. The tracker's `:clojure.tools.namespace.file/filemap` (already read in `check!`) maps each reloaded namespace to its file.

## Detection

Three checks, run inside `check!` after the reload loop succeeds and before `probe/run`, over the namespaces just reloaded (`targets`) unless noted.

### Check 1: declared refers that did not take (the field case)

For each reloaded namespace `N` with file `F` (from the filemap):

1. Read the `ns` form with `parse/read-ns-decl`; collect every `(lib :refer [syms] :rename {..})`, `(lib :refer :all)`, `(:use lib :only [..])` and `(:use lib)` into `(lib, local-sym, home-sym)` triples. `:refer :all`/`:use lib` expand against `(ns-publics lib)` *after* the reload.
2. For each triple: `expected = (get (ns-publics lib) home-sym)`, `actual = (get (ns-map N) local-sym)`.
3. Stale when `actual` is not identical to `expected`. Sub-classify: `:refused` when `(.ns actual)` is `N` itself (the REJECTED case: N interns the name), `:old-var` when `actual` is a Var of `lib` that is not the one `lib` now maps, `:missing` when `expected` is nil (the file refers a name `lib` no longer exports; cold would throw `IllegalAccessError`).
4. Corroborate: `*err*` is bound to a buffer around each `(require target :reload)`; lines matching `^REJECTED: attempt to replace interned var (\S+) with (\S+) in (\S+)` are parsed into `{:ns :kept :wanted}` and attached to the matching Check 1 entry as `:clojure-said`. Lines matching `^WARNING: (\S+) already refers to:` are recorded under `:reload-warnings`. The buffered text is also forwarded to the real `*err*` so nothing is swallowed.

### Check 2: interned vars the file no longer defines

Before the reload loop, for each namespace in `targets` that already exists, tag every interned var: `(alter-meta! v assoc ::gen G)` where `G` is this check's id. After its reload, any var in `(ns-interns N)` that still carries `::gen G` *and* has a `:file` in its metadata was not defined by this evaluation of the file: a stale definition (moved, renamed or deleted). Vars with no `:file` (runtime `intern`, test stubs) are counted under `:dynamic` and not reported. Each entry: `{:ns N :var sym :defined-at "src/pdfreader/core.clj:214" :class :stale-def}` using the surviving `:file`/`:line` metadata, which point at where the definition *used to be*.

This is the namespace-level form of AGENT-PROBE-RELOAD-017's concern (deleted test vars surviving), stated for every var.

### Check 3: referrers holding a stale var

For every stale var `v` found by Check 2, and every `:old-var` from Check 1, sweep `(all-ns)` for namespaces whose `ns-map` contains a value identical to `v` (excluding `clojure.core` refers for cost). Each hit is `{:ns R :sym s :refers v :home (.ns v) :class :stale-referrer :in-closure? bool}`. A referrer *inside* the closure was just reloaded and is still stale, which means the home namespace still exports the old var (Check 2 found it); a referrer *outside* the closure was never reloaded and will only be repaired by reloading it.

Compiled call sites hold the Var object, not the mapping, so a referrer calling `(foo)` keeps calling the old root whether or not its mapping is repaired; Check 3's `:in-closure?` says whether a repair (below) reaches it.

### Cost

- Tagging: one `alter-meta!` per interned var in the closure; a few thousand vars, microseconds each: about a millisecond.
- Check 1: one `read-ns-decl` per reloaded file (the top form only) plus a map lookup per declared refer. Milliseconds for a 50-namespace closure.
- Check 2: one metadata read per interned var: sub-millisecond.
- Check 3: only when Checks 1–2 found something; then one pass over `all-ns` × `ns-map` values with identity comparison, filtering `clojure.core`. On the order of 10⁴–10⁵ identity checks: milliseconds.
- `*err*` buffering: a `StringWriter` per reload; negligible.

Target: under 5 ms added to a check whose reload already costs hundreds of milliseconds, recorded as `:stale-check-ms` so the cost is a receipt fact.

### Interaction with tools.namespace order

Dependencies reload before dependents (`::track/load` order), so when a dependent's `ns` form runs its `:refer`, the home namespace has already been re-evaluated and `(ns-publics lib)` reflects the new file, except for the stale interns Check 2 catches. Check 1 therefore runs *after the whole loop*, not per namespace: a refer evaluated mid-loop against a not-yet-reloaded home would be a false `:old-var`. The closure is the requested namespaces' *dependencies*, not their dependents; dependents outside the closure are Check 3's `:in-closure? false` population and the reason a warm green on namespace X says nothing about a namespace Y that refers X.

## What is reported, and where

Additive (tenet 3).

**Warm receipt** (`check!`): `:stale {:refers [...] :defs [...] :referrers [...] :reload-warnings [...] :repaired [...] :check-ms n}`; when any `:refers` or `:referrers` entry remains after repair (or repair is off), `:status "fail"`, `:stage "stale-image"`, and one entry per such mapping in `:failures` with `:kind "stale"`, `:test "<ns>/<sym>"`, `:at "<file>:<line>"` (the referring `ns` form's file, line from `read-ns-decl` metadata when present), and a `:message` that names the fix:

```
pdfreader.core maps render-page to #'pdfreader.core/render-page, but its ns form refers it from pdfreader.render (Clojure: REJECTED, you must ns-unmap first). Stale definition at src/pdfreader/core.clj:214 (file no longer defines it).
```

**Terminal** (`render_failures` in `bin/sublime-loop`, already handles `fail` with arbitrary `:kind`): the entry prints as `STALE pdfreader.core/render-page  src/pdfreader/core.clj:3` with the message. One additional stderr line when stale *defs* exist without any referrer: `test-focus: 3 stale definitions survive in the warm image (not referenced); see receipt :stale`.

**Kaocha receipt**: unchanged; the condition is established before Kaocha runs.

## Verdict

Tenet 1: a `pass` requires that the code which ran is the code the files describe. When a reloaded namespace's declared mapping is not its actual mapping, or a referrer holds a var its home no longer exports, that is false for the namespaces involved; a green from them is a false green by construction (the field case *was* one). So:

- Any `:refers` or `:referrers` entry remaining after repair → `fail`, stage `stale-image`, never `pass`, never `unknown`. The JVM stays usable (`:jvm-usable true`): nothing is broken, the image is just wrong, and the next check may repair it.
- Stale `:defs` with no referrer (a deleted function nobody maps) → warning only; the verdict stands. Nothing compiled can reach the var except by `resolve`/`find-var` at runtime, which is out of reach of a static check; it is listed so a reader can see what the cold JVM will not have. A stale *test* var is the exception: it would run as a test that no longer exists; it is reported as `:refers`-class severity (and is AGENT-PROBE-RELOAD-017's case).

## Repair

Clojure's own message names the repair: `ns-unmap` first. Repair, when enabled, runs inside the same `check!` between detection and the probe:

1. For each `:refused` entry and each stale def that has a referrer: `(ns-unmap N sym)`.
2. `(require N :reload)` again for each namespace unmapped in step 1 (dependency order within the closure), so the `ns` form's `refer` now takes and any surviving code in the file redefines what it defines.
3. Re-run Checks 1–3. The verdict may be `pass` only if the re-check is clean; the receipt lists `:repaired [{:ns :var :was :now}]`.
4. Referrers with `:in-closure? false` cannot be repaired by this check (they were never reloaded): they stay in `:referrers`, so the status stays `fail`, with the message "reload <R> (name it in the check, or run the cold gate)".

Repair is **off in the first increment** (`test-focus --repair-stale` / `:kaocha-sublime/repair-stale true` in `tests.edn`) and the plan is to default it on once the field trial shows the re-check never passes on a still-stale image. The alternative is a cold start, which costs 12–35 s on pdfreader (LOOP-RELOAD-FAILURE-005 and LOOP-ENV-FINGERPRINT-004 both exist to avoid exactly that bill).

Why repair is safe for the cases it targets: an unmapped var object stays alive for any compiled caller that holds it (no NPE); its root is the old function, which was already the stale behaviour, and Check 3 names those callers. `defonce` state is lost only for a var the file no longer defines, which is state the cold JVM would not have either. Records and protocols: a moved `defrecord` leaves `->Foo`/`map->Foo` vars that unmap like any other; the class itself stays loaded, as it would after any `require :reload`.

## `:refer` versus alias guidance

The SETUP §14 recipe ("call through the alias after a move") stands and gains its reason: an alias-qualified call `render/render-page` resolves through `render`'s mapping at compile time, so a reload of the caller picks up the new var; a `:refer`ed bare name resolves through the *caller's* mapping, which is exactly the thing that goes stale. The detector does not forbid `:refer`; it tells the truth about it. A project-level lint ("no `:refer` of project namespaces in `src/`") is a separate, optional ratchet (house rule 19, rung a).

## False positives

- **Intentional re-exports with `def`**: `(def foo other/foo)` is an interned var whose root is a function; the file defines it on every reload, so Check 2's tag is wiped, and Check 1 does not involve it (no `:refer`). Not flagged.
- **`potemkin/import-vars`** interns a new var in the importing namespace at load with copied metadata (so `:file` is the *source* var's file): the import is re-run on reload, the tag is wiped, not flagged by Check 2. Check 1 ignores it. Potemkin's var watch keeps the copy in sync; nothing here interferes with it. Unverified against potemkin's current version; listed as a fixture to add.
- **Macros**: a `:refer`ed macro var is a var and is checked like any other. What no var check can see is a *stale expansion* already compiled into a caller; that is a false negative, below.
- **Runtime `intern`/stubs**: no `:file` metadata, counted as `:dynamic`, not reported.
- **Conditional definitions** (`(when windows? (defn foo ...))`): Check 2 flags `foo` when the branch is not taken on reload. The cold JVM would not have `foo` either, so the flag is correct, if surprising; the message says "file did not define it on this load".
- **`:rename`**: handled by the `local-sym`/`home-sym` split in Check 1.
- **`:refer-clojure :exclude`** then own `defn` of a core name: the own var is interned and defined by the file; no `:refer` is declared for it; not flagged.
- **Reloading mid-edit** (a file saved between scan and reload): already covered by LOOP-RELOAD-FAILURE-005 and the fingerprint `stale` status; the stale-image check runs only after a successful reload loop.

## False negatives

- A caller that already expanded a `:refer`ed macro keeps the old expansion until *it* reloads; var identity is clean.
- A referrer outside the closure that maps a var whose home still exports it, but whose *root* changed (ordinary redefinition): correct by design (vars are the indirection), not stale.
- Code that captured a function value (`(def handler (partial foo))`, a handler table) holds the old function object, not the var: invisible to every mapping check; it is why the cold gate stays.
- Stale **classes** from `deftype`/`defrecord`/`gen-class` re-evaluation (instances of the old class in state): outside this design.
- `:refer :all` against a lib that *gained* a name shadowing a local def: Clojure refuses it with the same REJECTED line, which Check 1 catches only if the file declares the refer; it does (`:all`), so it is caught.

## Decisions and alternatives

| Decision | Chosen | Alternatives considered | Rationale |
|---|---|---|---|
| Ground truth for "what the file defines" | Generation tag on var metadata before reload; survivors are stale | Parse every `def*` form in the file (fragile across macros); compare `ns-interns` before/after (old vars survive, so no difference); `remove-ns` before reload | `def` always rewrites metadata (verified for `defn def declare defonce defmulti`); the tag is exact, cheap and macro-agnostic |
| Ground truth for "what the file refers" | `read-ns-decl` on the tracker's file, own extraction of `:refer`/`:use` | Trust `ns-refers` after reload (it *is* the possibly-stale state) | The declared form is what the cold JVM will evaluate; the comparison must be declared versus actual |
| Corroboration | Buffer `*err*` around each reload; parse REJECTED/WARNING lines; forward the text | Ignore stderr | Clojure already states the exact var pair; capturing it costs nothing and lands the sentence the agent needs. Verified capturable via `binding [*err*]` |
| When to run | After the full reload loop, inside `check!`, before the probe | Per-namespace during the loop; a separate Kaocha plugin hook | Dependency order means a mid-loop check sees homes not yet reloaded (false `:old-var`); the condition is about the image, not a test |
| Scope of Checks 1–2 | The reloaded closure | Whole image every check | Cost, and the closure is where this check's reload could have gone wrong; Check 3 widens to `all-ns` only when there is something to look for |
| Verdict | `fail` / `stage stale-image` for referenced staleness; warn for unreferenced stale defs | Warn only; `unknown` | Tenet 1: the field case was a green on code the files did not contain. `unknown` would taint the runner and buy a cold start for a condition the runner can name |
| Repair | `ns-unmap` + re-`require :reload` + re-check, opt-in first, default later | Never repair (always say "cold start"); `remove-ns` the namespace (tools.namespace `refresh` style) | The REJECTED message names `ns-unmap` as the repair; it is milliseconds against a 12–35 s cold start. `remove-ns` kills `defonce` state and protocol identity for *all* vars, and forces reloading every dependent, which is the cost the runner's `require :reload` choice avoids. A repair that is not re-verified could itself be a false green, so the re-check gates the verdict |
| Referrers outside the closure | Report, do not reload them | Reload every dependent (tools.namespace `refresh` semantics) | Reloading dependents widens the probe scope beyond what the caller asked (AGENT-REQUESTED-SCOPE-020) and costs time per check; naming them lets the agent add them to the check |
| Output | Additive `:stale` map; stale mappings also as `:failures` entries of `:kind "stale"` | A new top-level status value | `render_failures` already prints any `:kind`; a new status would need every reader to learn it |
| Guidance | Keep "alias after a move"; add the reason | Forbid `:refer` | The detector removes the need to forbid; a lint is a separate optional ratchet |

## Tests (proposed names, for the registry `:tests`)

Clojure, against fixture namespaces under `fixtures/` where a second version of a file moves a function:

- `:a-refused-refer-after-a-move-is-reported-and-never-a-pass`
- `:a-stale-definition-the-file-no-longer-has-is-listed-with-its-old-location`
- `:a-referrer-outside-the-closure-holding-a-stale-var-is-named-with-in-closure-false`
- `:redefinition-clears-the-generation-tag-for-defn-def-declare-defonce-and-defmulti`
- `:runtime-interned-vars-are-counted-as-dynamic-not-stale`
- `:repair-unmaps-reloads-and-passes-only-when-the-recheck-is-clean`
- `:the-rejected-stderr-line-is-captured-attached-and-still-forwarded`

Python (`bin/sublime-loop`): `test_a_stale_image_fail_is_terminal_and_keeps_the_jvm_warm`, `test_stale_entries_render_like_failures`.

## Proposed registry intents

IDs continue after `LOOP-PERF-MODE-008`, which uncommitted code in the working tree already tags (the committed registry ends at `LOOP-RELOAD-FAILURE-005`; 006–008 are reserved by that work). The leak-detection design proposes 009–010.

```edn
{:id :LOOP-STALE-REFER-011 :status :proposed
 :ears "After a warm check's reload loop succeeds, the runner shall compare each reloaded namespace's declared refers (its ns form, including :refer :all, :use and :rename) with its actual mappings, and tag every pre-existing interned var before reload so that a var still tagged after reload is reported as a stale definition with its former file:line. When a declared refer does not map to the var its home namespace now exports, or a namespace maps a stale var another namespace still exports, the receipt shall report status fail, stage stale-image, jvm-usable true, a stale map (refers, defs, referrers with in-closure?, captured REJECTED/WARNING reload lines, check-ms), and one failures entry of kind stale per stale mapping naming the namespace, symbol, actual and expected var and the repair; it shall never report pass. Stale definitions with no referrer shall be listed and shall not change the verdict."
 :misreadings ["require :reload removes definitions the file no longer has." "A warm green on namespace X attests namespaces that refer X." "Clojure's REJECTED line on the runner's stderr is a report." "Comparing ns-interns before and after a reload finds deleted vars." "A stale image is an unknown that needs a cold start."]
 :boundaries ["Function moved, old namespace refers it (REJECTED)" "Function deleted, referrer outside the closure" "Function deleted, nobody refers it" ":refer :all and :use" ":rename" "Re-export by def" "potemkin import-vars" "Runtime intern without :file" "Conditional def not taken" "Home not yet reloaded (order)" "Deleted test var"]
 :tests [:a-refused-refer-after-a-move-is-reported-and-never-a-pass :a-stale-definition-the-file-no-longer-has-is-listed-with-its-old-location
         :a-referrer-outside-the-closure-holding-a-stale-var-is-named-with-in-closure-false
         :redefinition-clears-the-generation-tag-for-defn-def-declare-defonce-and-defmulti
         :runtime-interned-vars-are-counted-as-dynamic-not-stale :the-rejected-stderr-line-is-captured-attached-and-still-forwarded
         :test_a_stale_image_fail_is_terminal_and_keeps_the_jvm_warm :test_stale_entries_render_like_failures]
 :rationale "ks-2gv, pdfreader 2026-10-31: after clj-surgeon moved functions from pdfreader.core to pdfreader.render and added :refer, the warm JVM kept core's old vars (Clojure 1.12 REJECTED the refer on stderr and kept the interned var; verified by probe), with-redefs on the new vars missed, and the suite passed warm and failed cold."}
{:id :LOOP-STALE-REPAIR-012 :status :proposed
 :ears "When stale repair is enabled and a check finds refused refers or stale definitions with referrers inside the reloaded closure, the runner shall ns-unmap those vars, reload the affected namespaces again in dependency order, and re-run the stale checks; the verdict may be pass only when the re-check is clean, the receipt shall list each repair (namespace, var, was, now), and referrers outside the closure shall remain reported as stale so the status stays fail. Repair shall never remove-ns, never reload namespaces outside the closure, and never run when repair is disabled."
 :misreadings ["A repair that was applied proves the image is now correct." "Unmapping breaks compiled callers holding the var." "remove-ns is the same repair with fewer steps."]
 :boundaries ["Refused refer, no other referrer" "Stale def with an in-closure referrer" "Stale def with an out-of-closure referrer" "Re-check still stale" "Repair disabled" "defonce state on a var the file no longer defines"]
 :tests [:repair-unmaps-reloads-and-passes-only-when-the-recheck-is-clean]
 :rationale "Clojure's own message names ns-unmap as the repair; it costs milliseconds where a cold start costs 12-35 s on pdfreader. An unverified repair would be a second route to a false green, so the re-check gates the verdict."}
```

## Open questions

1. **`:kind "stale"` in `:failures`** reuses LOOP-FAILURE-DETAILS-001's vector for a non-assertion condition, as `:kind "load"` already does for reload failures. Confirm readers treat unknown kinds as opaque (the Python renderer does) before relying on it.
2. **Stale test vars and AGENT-PROBE-RELOAD-017.** The registry says the supported probe unloads named namespaces before reloading; `check!` does not, and `src/` has no `remove-ns`. Either the probe path that satisfies 017 lives elsewhere, or 017's test covers a narrower path than its EARS. Resolve before Check 2's "stale test var" rule is written, so the two do not disagree.
3. **Repair default.** How many field checks with repair on, and zero re-check-still-stale receipts, before the default flips? Proposal: one week on pdfreader and curtaincall.
4. **Dependents outside the closure.** When Check 3 names an out-of-closure referrer, should `test-focus` offer `--include-referrers` to add it to the same check (one reload, one probe) rather than asking the agent to retype the command?
5. **The different-home `IllegalStateException` path** ("already refers to", bytecode present) was not reached by any probe. Find the construction that triggers it and decide whether it is a reload failure (005) or a stale case (011).
6. **potemkin and other var-copying libraries**: add a fixture, or declare them out of scope with a `:dynamic`-style count?
7. **Whole-image sweep on demand** (`test-focus --stale-sweep`): Checks 1–2 over every loaded project namespace, for the moment after a large refactor when the closure is not known. Cost is tens of milliseconds; is the extra flag worth the surface?
