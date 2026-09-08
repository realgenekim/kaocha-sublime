#!/usr/bin/env python3
"""Bounded full MVR suite; isolated worktree, exact owned PID, heap and RSS separate."""
import os
from pathlib import Path
import shutil
import statistics
import subprocess
import time
from agent_witnesses import EV, FX, REPO, mark, parse_events, record, status

root = FX / os.environ.get('AGENT_MVR_FIXTURE', 'mvr')
if not root.exists():
    subprocess.run(['git', '-C', '/home/forge/src/marvin-voice-remote', 'worktree', 'add',
                    '--detach', str(root), '9439370'], check=True, capture_output=True)
assert not (root / 'target/kaocha-runs').exists(), 'Fresh receipt directory required'
cp = Path('/var/tmp/forge/kaocha-status-fx/classpath.txt').read_text().strip()
cmd = ['java', '-Xms64m', '-Xmx512m', '-Dguardrails.enabled=true', '-Dmvr.data.dir=target/test-data',
       '-cp', cp, 'clojure.main', '-m', 'kaocha.runner',
       '--plugin', 'kaocha.plugin/affected', '--plugin', 'kaocha.plugin/run-receipt',
       '--reporter', 'kaocha.report/agent']
log = (EV / 'mvr.log').open('wb')
start = time.perf_counter()
p = subprocess.Popen(cmd, cwd=root, env=dict(os.environ, STORE_BACKEND='jsonl'),
                     stdout=log, stderr=subprocess.STDOUT)
peak_kb = 0
try:
    deadline = start + 180
    while p.poll() is None:
        assert time.perf_counter() < deadline, 'MVR full-suite deadline'
        try:
            stats = Path(f'/proc/{p.pid}/status').read_text()
            rss = next((int(line.split()[1]) for line in stats.splitlines() if line.startswith('VmRSS:')), 0)
            peak_kb = max(peak_kb, rss)
        except FileNotFoundError:
            pass
        time.sleep(.05)
    wall = time.perf_counter() - start
    assert p.returncode == 0, (p.returncode, 'Inspect retained mvr.log')
    streams = list((root / 'target/kaocha-runs').glob('*.events'))
    assert len(streams) == 1, streams
    raw, events = parse_events(streams[0])
    assert [prefix for prefix, _ in events] == ['RUN-START', 'RUN-DONE']
    done = events[-1][1]
    assert done['outcome'] == 'pass' and done['fail'] == done['error'] == 0, done
    assert done['tests'] == 579, done
    token = mark(root, 'test/marvin_voice_remote/auth_test.clj')
    answer = status(root, '--follow', '--token', token)
    assert answer[0] == 0 and answer[1] == 'RUN-DONE', answer
    samples = [status(root, '--follow', '--token', token)[3] for _ in range(10)]
    shutil.copy2(streams[0], EV / 'mvr.events')
    shutil.copy2(done['receipt'], EV / 'mvr.edn')
    record('mvr-full-suite-512m', command=cmd, pid=p.pid, specimen_sha='9439370',
           wall_s=wall, peak_sampled_rss_kb=peak_kb, protocol_bytes=len(raw), done=done,
           follow_samples_ms=samples, follow_median_ms=statistics.median(samples))
finally:
    if p.poll() is None:
        p.kill()
        p.wait(timeout=10)
    log.close()
