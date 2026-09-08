#!/usr/bin/env python3
"""Actual SIGKILL and independent-JVM receipt witnesses, no service ports."""
import os, pathlib, re, shutil, signal, subprocess, time
REPO = pathlib.Path(__file__).resolve().parents[1]
FX = pathlib.Path('/var/tmp/forge/kaocha-sublime-fx/process')
FX.mkdir(parents=True, exist_ok=True)
cp = subprocess.check_output(['clojure', '-Spath'], cwd=REPO, text=True).strip()
cp = ':'.join(str((REPO / p).resolve()) for p in cp.split(':')) + ':' + str(REPO / 'test')

def launch(name, fixture='tiny', mode='api', extra_env=None):
    root = FX / name
    if root.exists(): shutil.rmtree(root)
    shutil.copytree(REPO / 'fixtures' / fixture, root)
    out = root / 'receipts'
    log = open(FX / (name + '.log'), 'w')
    env = dict(os.environ, **(extra_env or {}))
    cmd = ['java', '-Xmx256m', '-cp', cp + ':' + str(root / 'src'),
           'clojure.main', '-m', 'kaocha-sublime.driver', mode, str(root), str(out)]
    return subprocess.Popen(cmd, cwd=root, env=env, stdout=log, stderr=subprocess.STDOUT), root, log

p, root, log = launch('killed', 'block', extra_env={'BLOCK_READY': str(FX / 'ready')})
try:
    deadline = time.monotonic() + 60
    while not (FX / 'ready').exists():
        assert p.poll() is None, 'Blocking fixture exited early'
        assert time.monotonic() < deadline, 'Blocking fixture did not start'
        time.sleep(.05)
    assert int((FX / 'ready').read_text()) == p.pid
    assert len(list((root / 'receipts').glob('*.tmp'))) == 1
    os.kill(p.pid, signal.SIGKILL)
    assert p.wait(timeout=10) == -signal.SIGKILL
    assert not list((root / 'receipts').glob('*.edn'))
    print('PASS SIGKILL exact JVM pid', p.pid, ': one tmp, zero edn')
finally:
    if p.poll() is None: p.kill(); p.wait()
    log.close()
    (FX / 'ready').unlink(missing_ok=True)

runs = [launch('concurrent-a'), launch('concurrent-b')]
ids = []
try:
    for p, root, log in runs:
        assert p.wait(timeout=90) == 0
        log.close()
        files = list((root / 'receipts').glob('*.edn'))
        assert len(files) == 1 and not list((root / 'receipts').glob('*.tmp'))
        ids.append(files[0].stem)
        lines = (FX / (root.name + '.log')).read_text().splitlines()
        assert lines[-1] == 'RUN-RECEIPT ' + str(files[0])
        assert ':tests 5, :assertions 4, :pass 2, :fail 1, :error 1, :pending 1' in files[0].read_text()
    assert len(set(ids)) == 2
    print('PASS concurrent isolated JVMs: distinct ids, totals, separate complete markers')
finally:
    for p, _, log in runs:
        if p.poll() is None: p.kill(); p.wait()
        log.close()
