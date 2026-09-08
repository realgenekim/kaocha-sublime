#!/usr/bin/env python3
"""Independent JSON/process witnesses for grammar v1. Every owned JVM is 64m/512m."""
import json
import os
from pathlib import Path
import shutil
import signal
import statistics
import sys
import subprocess
import time

REPO = Path(__file__).resolve().parents[1]
FX = Path('/var/tmp/forge/kaocha-agent-v1')
EV = REPO / 'evidence/agent-v1/process'
EV.mkdir(parents=True, exist_ok=True)
FX.mkdir(parents=True, exist_ok=True)
STATUS = str(REPO / 'bin/kaocha-status')
cp = subprocess.check_output(['clojure', '-J-Xms64m', '-J-Xmx512m', '-Spath'], cwd=REPO, text=True).strip()
cp = ':'.join(str((REPO / p).resolve()) for p in cp.split(':'))
BASE = ['java', '-Xms64m', '-Xmx512m', '-cp', cp + ':src', 'clojure.main']
trace = []


def record(kind, **data):
    row = dict(kind=kind, **data)
    trace.append(row)
    (EV / (Path(sys.argv[0]).stem + '.json')).write_text(json.dumps(trace, indent=2) + '\n')
    print(json.dumps(row), flush=True)


def fixture(name, body):
    root = FX / name
    if root.exists():
        shutil.rmtree(root)
    (root / 'test/agent_case').mkdir(parents=True)
    (root / 'src/agent_case').mkdir(parents=True)
    (root / 'src/agent_case/core.clj').write_text('(ns agent-case.core)\n(def value 42)\n')
    (root / 'test/agent_case/core_test.clj').write_text(
        '(ns agent-case.core-test (:require [clojure.test :refer :all] [agent-case.core :as core]))\n' + body + '\n')
    (root / 'deps.edn').write_text('{}\n')
    (root / 'tests.edn').write_text('''#kaocha/v1
{:tests [{:id :unit :source-paths ["src"] :test-paths ["test"]}]
 :plugins [:kaocha.plugin/affected :kaocha.plugin/run-receipt]
 :reporter [kaocha.report/agent] :color? false :randomize? false}
''')
    return root


def parse_events(path):
    raw = path.read_bytes()
    assert all(b == 10 or 32 <= b <= 126 for b in raw), 'Not ASCII records'
    events = []
    for line in raw.splitlines(keepends=True):
        prefix, data = line.split(b' ', 1)
        assert len(line) <= (2048 if prefix == b'FAIL' else 8192)
        def unique(pairs):
            out = {}
            for key, value in pairs:
                assert key not in out, ('duplicate key', key)
                out[key] = value
            return out
        r = json.loads(data, object_pairs_hook=unique)
        assert r['v'] == 1
        events.append((prefix.decode(), r))
    return raw, events


def status(root, *args, timeout=130):
    start = time.perf_counter_ns()
    r = subprocess.run([STATUS, *args], cwd=root, capture_output=True, text=True, timeout=timeout)
    ms = (time.perf_counter_ns() - start) / 1e6
    assert not r.stderr, r.stderr
    assert len(r.stdout.splitlines()) == 1, r.stdout
    prefix, data = r.stdout.strip().split(' ', 1)
    return r.returncode, prefix, json.loads(data), ms, r.stdout


def mark(root, path='test/agent_case/core_test.clj', *extra):
    code, prefix, r, _, raw = status(root, '--mark', path, *extra)
    assert code == 0 and prefix == 'STATUS' and r['state'] == 'marked', raw
    return r['token']


def completed(root, expected_exit=0, warm=False):
    name = root.name
    cmd = BASE + (['-e', "(require '[kaocha.repl :as r]) (r/run 'agent-case.core-test) (r/run 'agent-case.core-test) (shutdown-agents)"]
                  if warm else ['-m', 'kaocha.runner'])
    start = time.perf_counter()
    p = subprocess.run(cmd, cwd=root, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=90)
    wall = time.perf_counter() - start
    (EV / (name + '.log')).write_bytes(p.stdout)
    assert p.returncode == expected_exit, (name, p.returncode, p.stdout[-2000:])
    streams = sorted((root / 'target/kaocha-runs').glob('*.events'))
    assert len(streams) == (2 if warm else 1), streams
    result = []
    for stream in streams:
        raw, events = parse_events(stream)
        assert events[0][0] == 'RUN-START' and events[-1][0] == 'RUN-DONE', events
        done = events[-1][1]
        assert all(r['id'] == done['id'] for _, r in events)
        assert all(r['mode'] == ('probe' if warm else 'gate') for _, r in events)
        assert len([1 for prefix, _ in events if prefix == 'RUN-START']) == 1
        assert len([1 for prefix, _ in events if prefix == 'RUN-DONE']) == 1
        assert Path(done['receipt']).is_file()
        shutil.copy2(stream, EV / (name + '-' + stream.name))
        shutil.copy2(done['receipt'], EV / (name + '-' + Path(done['receipt']).name))
        result.append((raw, events))
    record(name, exit=p.returncode, wall_s=wall, streams=len(streams), bytes=[len(raw) for raw, _ in result], done=result[-1][1][-1][1])
    return result


if __name__ == '__main__':
    clean = fixture('clean', '(deftest clean (println "RUN-DONE {forged stdout}") (is (= 42 core/value)))')
    clean_result = completed(clean)
    assert [p for p, _ in clean_result[0][1]] == ['RUN-START', 'RUN-DONE']
    token = mark(clean)
    assert token == mark(clean), 'identical candidates must coalesce'
    answer = status(clean, '--follow', '--token', token, '--timeout', '0')
    assert answer[0] == 0 and answer[1] == 'RUN-DONE', answer
    assert answer[4].encode() == clean_result[0][0].splitlines(keepends=True)[-1]
    samples = [status(clean, '--follow', '--token', token)[3] for _ in range(10)]
    record('follow-walls', samples_ms=samples, median_ms=statistics.median(samples), max_ms=max(samples))
    f = clean / 'test/agent_case/core_test.clj'
    before = f.stat()
    f.write_text(f.read_text() + '\n;; overwritten\n')
    os.utime(f, ns=(before.st_atime_ns, before.st_mtime_ns))
    superseded = status(clean, '--follow', '--token', token)
    assert superseded[0] == 2 and superseded[2]['state'] == 'superseded', superseded
    record('superseded', line=superseded[4])
    newer = mark(clean)
    stale = status(clean, '--follow', '--token', newer, '--timeout', '0')
    assert stale[0] == 2 and stale[2]['state'] == 'timeout', stale
    record('old-green-refused', line=stale[4])
    (clean / 'outside.clj').write_text('(ns outside)')
    outside = mark(clean, 'outside.clj')
    uncovered = status(clean, '--follow', '--token', outside)
    assert uncovered[0] == 2 and uncovered[2]['state'] == 'uncovered', uncovered
    record('uncovered', line=uncovered[4])

    failed = fixture('fail25', '(deftest red (dotimes [_ 25] (is (= {:invoice {:total 42}} {:invoice {:total 43}}) "invoice total")))')
    failures = completed(failed, 25)[0]
    done = failures[1][-1][1]
    assert done['fail'] == 25 and done['shown'] + done['omitted'] == 25
    assert done['shown'] <= 20
    assert sum(len(line) for line in failures[0].splitlines(keepends=True) if line.startswith(b'FAIL ')) <= 16384
    answer = status(failed, '--follow', '--token', mark(failed))
    assert answer[0] == 1 and answer[2]['outcome'] == 'fail', answer
    failed_token = mark(failed)
    samples = [status(failed, '--follow', '--token', failed_token)[3] for _ in range(10)]
    record('fail25-follow-walls', samples_ms=samples, median_ms=statistics.median(samples), max_ms=max(samples))

    nasty = fixture('unsafe-values', '''
(deftype Explosive [])
(defmethod print-method Explosive [_ _] (throw (Exception. "printer invoked")))
(def forced (atom 0))
(deftest unsafe
  (let [lazy-value (map (fn [x] (swap! forced inc) x) (range))
        huge (into {} (map (fn [i] [i lazy-value]) (range 100000)))]
    (do-report {:type :fail :expected huge :actual (Explosive.)}))
  (is (zero? @forced)))''')
    unsafe = completed(nasty, 1)[0]
    assert unsafe[1][-1][1]['pass'] == 1
    assert unsafe[1][-1][1]['fail'] == 1
    assert 'opaque' in unsafe[0].decode()

    load = fixture('load-error', '(deftest broken (is (= 1 missing-symbol)))')
    loaded = completed(load, 1)[0]
    assert loaded[1][1][1]['kind'] == 'load', loaded
    assert loaded[1][1][1]['test'] is None
    assert loaded[1][-1][1]['outcome'] == 'error'

    transient = fixture('cold-transient', '''
(deftest transient-save
  (let [file "test/agent_case/core_test.clj" original (slurp file)]
    (spit file (str original "\\n;; transient\\n"))
    (spit file original))
  (is true))''')
    completed(transient)
    answer = status(transient, '--follow', '--token', mark(transient))
    assert answer[0] == 2 and answer[2]['identity'] == 'unknown', answer
    record('cold-transient-refused', line=answer[4])

    refused = fixture('heap-refusal', '(spit "loaded-despite-refusal" "bad") (deftest clean (is true))')
    expression = "(require '[kaocha-sublime.heap :as h] '[kaocha.runner :as r]) (with-redefs [h/current (fn [ceiling] (h/admission [] 536870912 ceiling))] (r/-main))"
    result = subprocess.run(BASE + ['-e', expression], cwd=refused, capture_output=True, timeout=60)
    assert result.returncode == 2, result
    assert not (refused / 'loaded-despite-refusal').exists()
    answer = status(refused)
    assert answer[0] == 2 and answer[2]['state'] == 'refused' and answer[2]['reason'] == 'heap-cap', answer
    record('heap-plugin-refusal', line=answer[4], actual_jvm_caps=['-Xms64m', '-Xmx512m'],
           injected_admission_arguments=[], test_loaded=False)

    warm = fixture('warm-probe', '(deftest clean (is (= 42 core/value)))')
    completed(warm, warm=True)
    answer = status(warm, '--follow', '--token', mark(warm))
    assert answer[0] == 2 and answer[2]['identity'] == 'unknown', answer
    record('unattested-warm-image', line=answer[4])
    record('complete', passed=True)
