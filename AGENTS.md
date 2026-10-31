# kaocha-sublime — agent instructions

Warm Kaocha test loop for Clojure agents. Start with `README.md` and `docs/SETUP.md`;
the design root is `docs/high-level-design.md`.

## Working here

- Verify with this repo's OWN warm JVM: `bin/warm-jvm --root <this checkout>`, then
  `bin/test-focus <test namespaces or ns/var>`. Never start, stop or query warm JVMs of
  other roots; other repos use them.
- Python suite: `python3 -m unittest discover -s test -p 'test_*.py'` (Python 3.11+).
- Cold gate before a commit: `clojure -J-Xms64m -J-Xmx512m -M:test`.
- Receipts and the JSON line are read by other programs: change them additively only.
- Track work in `bd` (beads).

## LID
- Mode: Full
- Version: 1.3.0

Linked-intent layout in this repo:

- **HLD:** `docs/high-level-design.md` (problem, approach, tenets).
- **Requirements (EARS):** `docs/intent/registry.edn` — ONE append-only registry for all
  topics, not per-topic `-specs.md` files. Each intent has a stable `:id`, `:ears` text,
  `:misreadings`, `:boundaries`, linked `:tests` and `:rationale`. Retire or supersede IDs;
  never delete or repurpose them. `test/kaocha_sublime/intent_contract_test.clj` enforces
  the links between intents and tests.
- **Design docs (LLDs):** `docs/intent/<topic>/<topic>-design.md` for features that need
  design before code; they cite registry IDs instead of carrying their own spec files.
- **Code and tests** cite intent IDs (`; INTENT: <ID>` / test names listed in `:tests`).

Changes walk the arrow: HLD → design doc (when one is needed) → registry intent → tests
first → code.
