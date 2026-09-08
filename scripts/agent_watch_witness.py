#!/usr/bin/env python3
"""One owned watch JVM; save/mark/follow observes the protocol, never raw stdout."""
import os
from pathlib import Path
import signal
import subprocess
import time
from agent_witnesses import BASE, EV, fixture, mark, parse_events, record, status

root = fixture('watch', '(deftest clean (is (= 42 core/value)) (when-not (.exists (java.io.File. \"src/agent_case/spare.clj\")) (is (nil? (find-ns (quote agent-case.spare))))))')
(root / 'src/agent_case/spare.clj').write_text('(ns agent-case.spare)\n(def spare 1)\n')
file = root / 'test/agent_case/core_test.clj'
runs = root / 'target/kaocha-runs'
log = (EV / 'watch.log').open('wb')
p = subprocess.Popen(BASE + ['-m', 'kaocha.runner', '--watch'], cwd=root, stdin=subprocess.PIPE,
                     stdout=log, stderr=subprocess.STDOUT)


def wait_path(path, timeout=30):
    deadline = time.monotonic() + timeout
    while not path.exists():
        assert p.poll() is None, 'Watcher exited; inspect watch.log'
        assert time.monotonic() < deadline, str(path)
        time.sleep(.02)


def save(text, same_mtime=False):
    stamp = file.stat()
    with file.open('w') as f:
        f.write(text)
        f.flush()
        os.fsync(f.fileno())
    if same_mtime:
        os.utime(file, ns=(stamp.st_atime_ns, stamp.st_mtime_ns))
    return mark(root)


def follow(token, expected=0):
    answer = status(root, '--follow', '--token', token, '--timeout', '30', timeout=40)
    record('watch-follow', exit=answer[0], line=answer[4], wall_ms=answer[3])
    assert answer[0] == expected, answer
    return answer


try:
    wait_path(runs / 'LATEST')
    initial = follow(mark(root))
    assert initial[1] == 'RUN-DONE' and initial[2]['mode'] == 'watch'
    original = file.read_text()
    red = original.replace('(= 42 core/value)', '(= 43 core/value)')
    failed = follow(save(red), 1)
    assert failed[2]['fail'] == 1
    failed_start = parse_events(runs / (failed[2]['id'] + '.events'))[1][0][1]
    assert failed_start['trigger'] == [str(file)], failed_start
    assert failed_start['trigger_omitted'] == 0
    green_token = save(original, same_mtime=True)
    green = follow(green_token)
    assert not green[2]['auto_followup']
    primary = green[2]['id']
    deadline = time.monotonic() + 10
    child = None
    while time.monotonic() < deadline:
        for stream in runs.glob('*.events'):
            raw, events = parse_events(stream)
            if events and events[-1][0] == 'RUN-DONE' and events[-1][1]['parent'] == primary:
                child = events[-1][1]
                break
        if child:
            break
        time.sleep(.03)
    assert child and child['auto_followup'], child
    assert child['parent_outcome'] == {'outcome': 'pass', 'fail': 0, 'error': 0}, child
    assert follow(green_token)[2]['id'] == primary
    record('automatic-followup-primary', primary=primary, child=child)

    # A real reload error is verified red; repair re-establishes the image.
    load_token = save(original.replace('(= 42 core/value)', '(= 42 missing-symbol)'))
    load_answer = follow(load_token, 1)
    assert load_answer[2]['outcome'] == 'error', load_answer
    record('watch-load-error', line=load_answer[4])
    repaired = follow(save(original))
    record('watch-reload-repaired', line=repaired[4])

    # Upstream ignores delete notifications. Enter is the explicit recovery;
    # the manifest still records the deletion and can only accept its bytes.
    spare = root / 'src/agent_case/spare.clj'
    spare.unlink()
    deleted_token = mark(root, 'src/agent_case/spare.clj')
    p.stdin.write(b'\n')
    p.stdin.flush()
    deleted = follow(deleted_token)
    record('watch-deletion-after-enter', line=deleted[4])

    ready = root / 'ready'
    blocked = original.replace('(deftest clean', f'(deftest clean (spit "{ready}" (str (.pid (java.lang.ProcessHandle/current)))) (Thread/sleep 60000)')
    killed_token = save(blocked)
    wait_path(ready)
    assert int(ready.read_text()) == p.pid
    active = status(root, '--token', killed_token)
    assert active[2]['state'] in ['running', 'late'], active
    os.kill(p.pid, signal.SIGKILL)
    assert p.wait(timeout=10) == -signal.SIGKILL
    killed = follow(killed_token, 2)
    assert killed[2]['state'] == 'killed'
    for stream in runs.glob('*.events'):
        raw, events = parse_events(stream)
        if events and events[0][1]['id'] == killed[2]['id']:
            assert not any(prefix == 'RUN-DONE' for prefix, _ in events)
            (EV / 'killed.events').write_bytes(raw)
    record('killed', line=killed[4], pid=p.pid)
    record('watch-complete', passed=True)
finally:
    if p.poll() is None:
        p.kill()
        p.wait(timeout=10)
    log.close()
