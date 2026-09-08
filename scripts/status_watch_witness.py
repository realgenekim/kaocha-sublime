#!/usr/bin/env python3
"""Real mvr watcher: all verdict discovery uses kaocha-status, never log tails.

Prepare a fresh detached nrepl/test-alias worktree at FX/mvr and its :run-tests
classpath in FX/classpath.txt. Logs are retained solely for failure diagnosis.
One exact-PID JVM, no nREPL/server listener. Specimen edits stay in that worktree.
"""
import json
import os
from pathlib import Path
import shutil
import signal
import statistics
import subprocess
import time

REPO = Path(__file__).resolve().parents[1]
FX = Path('/var/tmp/forge/kaocha-status-fx')
ROOT = FX / 'mvr'
RUNS = ROOT / 'target/kaocha-runs'
EVIDENCE = REPO / 'evidence/status-watch'
EVIDENCE.mkdir(parents=True, exist_ok=True)
TRACE = EVIDENCE / 'witnesses.jsonl'
STATUS = str(REPO / 'bin/kaocha-status')
FILE = ROOT / 'test/marvin_voice_remote/auth_test.clj'


def record(kind, **data):
    row = dict(kind=kind, **data)
    with TRACE.open('a') as f:
        f.write(json.dumps(row, sort_keys=True) + '\n')
    print(json.dumps(row, sort_keys=True), flush=True)


def status(*args):
    started = time.perf_counter_ns()
    r = subprocess.run([STATUS, '--legacy', '--dir', str(RUNS), *args], cwd=ROOT,
                       capture_output=True, text=True, check=True)
    elapsed = (time.perf_counter_ns() - started) / 1e6
    assert not r.stderr, r.stderr
    assert len(r.stdout.splitlines()) == 1, r.stdout
    return r.stdout.strip(), elapsed


def edn(path):
    return json.loads(subprocess.check_output(
        ['bb', '--config', str(REPO / 'bb.edn'), '-e',
         '(require \'[clojure.edn :as e] \'[cheshire.core :as j]) '
         '(println (j/generate-string (e/read-string (slurp *in*))))'],
        input=Path(path).read_bytes()))


def receipt(line):
    fields = dict(x.split('=', 1) for x in line.split()[2:])
    r = edn(fields['receipt'])
    for key in ['tests', 'pass', 'fail', 'error']:
        assert int(fields[key]) == r['totals'][key], (line, r)
    assert fields['id'] == r['run-id']
    assert fields['finished'] == r['finished']
    shutil.copy2(fields['receipt'], EVIDENCE)
    return r


def wait_done(previous=None, since=None, predicate=lambda r: True, timeout=120):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        assert proc.poll() is None, 'Watcher exited: inspect retained watcher.log'
        line, _ = status(*(['--since', since] if since else []))
        if line.startswith('KAOCHA-STATUS DONE ') and 'id=' + str(previous) + ' ' not in line:
            r = receipt(line)
            if predicate(r):
                return line, r
        time.sleep(.05)
    raise AssertionError('Timed out waiting for typed verdict; last status: ' + line)


def save(text):
    with FILE.open('w') as f:
        f.write(text)
        f.flush()
        os.fsync(f.fileno())
    return FILE.stat().st_mtime_ns


assert not RUNS.exists(), 'Use a fresh worktree; do not mingle runs'
TRACE.write_text('')
original = FILE.read_text()
needle = '(is (nil? (auth/verify-session-cookie "garbage")))'
assert original.count(needle) == 1
red = original.replace(needle, '(is (some? (auth/verify-session-cookie "garbage")))')
cp = (FX / 'classpath.txt').read_text().strip()
command = ['java', '-Xms64m', '-Xmx512m', '-Dguardrails.enabled=true', '-Dmvr.data.dir=target/test-data',
           '-cp', cp, 'clojure.main', '-m', 'kaocha.runner', '--watch',
           '--plugin', 'kaocha.plugin/affected', '--plugin', 'kaocha.plugin/run-receipt']
log = (FX / 'watcher.log').open('w')
record('none-before-launch', line=status()[0])
proc = subprocess.Popen(command, cwd=ROOT, env=dict(os.environ, STORE_BACKEND='jsonl'),
                        stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT)
(FX / 'watcher.pid').write_text(str(proc.pid) + '\n')
record('launch', pid=proc.pid, command=command, specimen_sha=subprocess.check_output(
    ['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip())
try:
    line, initial = wait_done()
    record('initial', line=line, totals=initial['totals'])
    assert initial['totals']['tests'] == 579, initial['totals']
    assert initial['totals']['fail'] == initial['totals']['error'] == 0
    time.sleep(.3)

    mtime = save(red)
    immediate, wall = status('--since', str(FILE))
    record('save-immediate', line=immediate, mtime_ns=mtime, call_wall_ms=wall)
    assert immediate.split()[1] in ['RUNNING', 'DONE'], immediate
    line, failed = wait_done(initial['run-id'], str(FILE), lambda r: r['totals']['fail'] == 1)
    record('red-done', line=line, trigger=failed['trigger'])
    assert str(FILE) in failed['trigger']['files'], failed
    assert not failed['auto-followup?']

    mtime = save(original)
    immediate, wall = status('--since', str(FILE))
    record('green-save-immediate', line=immediate, mtime_ns=mtime, call_wall_ms=wall)
    line, green = wait_done(failed['run-id'], str(FILE), lambda r: r['totals']['fail'] == 0)
    record('green-save-done', line=line, trigger=green['trigger'])
    assert not green['auto-followup?']
    auto_line, auto = wait_done(green['run-id'], predicate=lambda r: r['auto-followup?'])
    record('automatic-followup', line=auto_line, auto_followup=auto['auto-followup?'],
           trigger=auto['trigger'], followup_of=auto['followup-of'])
    mine, _ = status('--since', str(FILE))
    record('since-still-my-run', line=mine)
    assert receipt(mine)['run-id'] == green['run-id']
    outside, _ = status('--ns', 'outside.this.closure')
    record('outside-closure', line=outside)
    assert ' covers=no ' in outside

    calls = [status()[1] for _ in range(10)]
    record('timings', milliseconds=calls, median_ms=statistics.median(calls), max_ms=max(calls))
    assert max(calls) < 200, calls

    subprocess.run(['sed', '-i', '$a;; sed-blindness-witness', str(FILE)], check=True)
    stale_line, _ = status('--since', str(FILE))
    record('sed-immediate', line=stale_line, mtime_ns=FILE.stat().st_mtime_ns)
    time.sleep(1.5)
    stale_later, _ = status('--since', str(FILE))
    record('sed-after-wait', line=stale_later)
    assert stale_later.startswith('KAOCHA-STATUS STALE '), stale_later
    assert edn(RUNS / 'LATEST')['run-id'] == auto['run-id'], 'sed triggered upstream'

    # Add a long test body in the owned specimen, preserving its assertion count.
    # READY proves the killed process is the very JVM executing that test.
    ready = FX / 'kill-ready'
    blocked = original.replace('(deftest session-cookie-roundtrip-test',
        '(deftest session-cookie-roundtrip-test\n'
        f'  (spit "{ready}" (str (.pid (java.lang.ProcessHandle/current))))\n'
        '  (Thread/sleep 60000)', 1)
    save(blocked)
    deadline = time.monotonic() + 30
    while not ready.exists():
        assert proc.poll() is None
        assert time.monotonic() < deadline, 'Blocking test never entered'
        time.sleep(.05)
    assert int(ready.read_text()) == proc.pid
    running, _ = status()
    record('kill-running', line=running, pid=proc.pid)
    assert running.startswith('KAOCHA-STATUS RUNNING ')
    # Saving during the run must be stale even though the eventual finish would
    # be newer. SIGKILL prevents that queued edit ever producing a verdict.
    save(blocked + '\n;; edit-during-run\n')
    during, _ = status('--since', str(FILE))
    record('edit-during-run', line=during)
    assert during.startswith('KAOCHA-STATUS STALE ')
    os.kill(proc.pid, signal.SIGKILL)
    assert proc.wait(timeout=10) == -signal.SIGKILL
    after_kill, _ = status()
    record('killed-before-reaper', line=after_kill)
    assert after_kill.startswith('KAOCHA-STATUS RUNNING ')
    reaped = subprocess.check_output([str(REPO / 'bin/kaocha-reap'), '--dir', str(RUNS)], text=True)
    record('reaper', line=reaped.strip())
    after_reap, _ = status()
    record('killed-after-reaper', line=after_reap)
    assert after_reap.startswith(('KAOCHA-STATUS NONE ', 'KAOCHA-STATUS STALE '))
    assert edn(RUNS / 'LATEST')['run-id'] == auto['run-id']
    shutil.copy2(RUNS / 'LATEST', EVIDENCE / 'LATEST')
    shutil.copy2(RUNS / 'INTERRUPTED', EVIDENCE / 'INTERRUPTED')
    record('complete', passed=True)
finally:
    if proc.poll() is None:
        os.kill(proc.pid, signal.SIGKILL)
        proc.wait(timeout=10)
    log.close()
    FILE.write_text(original)
