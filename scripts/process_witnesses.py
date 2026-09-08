#!/usr/bin/env python3
"""Actual SIGKILL and independent-JVM receipt witnesses, no service ports."""
import os, pathlib, re, shutil, signal, subprocess, time
REPO = pathlib.Path(__file__).resolve().parents[1]
FX = pathlib.Path('/var/tmp/forge/kaocha-sublime-fx/process')
FX.mkdir(parents=True, exist_ok=True)
cp = subprocess.check_output(['clojure', '-J-Xms64m', '-J-Xmx512m', '-Spath'], cwd=REPO, text=True).strip()
cp = ':'.join(str((REPO / p).resolve()) for p in cp.split(':')) + ':' + str(REPO / 'test')

def launch(name, fixture='tiny', mode='api', extra_env=None, shared_out=None):
    root = FX / name
    if root.exists(): shutil.rmtree(root)
    shutil.copytree(REPO / 'fixtures' / fixture, root)
    out = shared_out or root / 'receipts'
    log = open(FX / (name + '.log'), 'w')
    env = dict(os.environ, **(extra_env or {}))
    cmd = ['java', '-Xms64m', '-Xmx512m', '-cp', cp + ':' + str(root / 'src'),
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

shared = FX / 'shared-receipts'
if shared.exists(): shutil.rmtree(shared)
runs = [launch('concurrent-a', shared_out=shared), launch('concurrent-b', shared_out=shared)]
ids = []
try:
    for p, root, log in runs:
        assert p.wait(timeout=90) == 0
        log.close()
        files = [f for f in shared.glob('*.edn') if f':jvm-pid {p.pid},' in f.read_text()]
        assert len(files) == 1
        ids.append(files[0].stem)
        lines = (FX / (root.name + '.log')).read_text().splitlines()
        assert lines[-1] == 'RUN-RECEIPT ' + str(files[0])
        assert ':tests 5, :assertions 4, :pass 2, :fail 1, :error 1, :pending 1' in files[0].read_text()
    assert len(set(ids)) == 2
    assert len(list(shared.glob('*.edn'))) == 2 and not list(shared.glob('*.tmp'))
    print('PASS concurrent isolated JVMs, shared receipt directory: distinct ids, totals, separate complete markers')
finally:
    for p, _, log in runs:
        if p.poll() is None: p.kill(); p.wait()
        log.close()

p, root, log = launch('warm', mode='repl')
try:
    assert p.wait(timeout=90) == 0
    returned = (root / 'receipts/returned.edn').read_text()
    assert returned.count(':kaocha.plugin.run-receipt/path') == 2
    paths = re.findall(r':kaocha.plugin.run-receipt/path "([^"]+)"', returned)
    assert len(set(paths)) == 2 and all(pathlib.Path(x).exists() for x in paths)
    print('PASS first lazy plugin load inside warm repl/run, then second run: distinct returned paths')
finally:
    if p.poll() is None: p.kill(); p.wait()
    log.close()

runs = [launch('collision-a', extra_env={'KAOCHA_RUN_ID': 'same-id'}, shared_out=shared),
        launch('collision-b', extra_env={'KAOCHA_RUN_ID': 'same-id'}, shared_out=shared)]
try:
    codes = [p.wait(timeout=90) for p, _, _ in runs]
    assert codes.count(0) == 1 and codes.count(1) == 1, codes
    assert (shared / 'same-id.edn').exists() and not (shared / 'same-id.edn.tmp').exists()
    print('PASS fixed-id collision: one winner, one refusal, completed receipt retained')
finally:
    for p, _, log in runs:
        if p.poll() is None: p.kill(); p.wait()
        log.close()
