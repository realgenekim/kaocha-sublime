#!/usr/bin/env python3
"""Exercise the actual --affected-since CLI against an isolated Git fixture."""
import os, pathlib, shutil, subprocess
REPO = pathlib.Path(__file__).resolve().parents[1]
ROOT = pathlib.Path('/var/tmp/forge/kaocha-sublime-fx/cli')
if ROOT.exists(): shutil.rmtree(ROOT)
shutil.copytree(REPO / 'fixtures/tiny', ROOT)
(ROOT / 'deps.edn').write_text('{}\n')
(ROOT / '.gitignore').write_text('target/\n')
(ROOT / 'tests.edn').write_text('''#kaocha/v1
{:tests [{:id :unit :source-paths ["src"] :test-paths ["test"]}]
 :plugins [:kaocha.plugin/affected :kaocha.plugin/run-receipt]
 :color? false :randomize? false}
''')
subprocess.run(['git', 'init', '-q'], cwd=ROOT, check=True)
subprocess.run(['git', 'add', '.'], cwd=ROOT, check=True)
subprocess.run(['git', '-c', 'user.name=forge-anvil', '-c', 'user.email=forge-anvil@anvil',
                'commit', '-qm', 'fixture\n\nCo-Authored-By: Gene Kim <genek@itrevolution.com>'], cwd=ROOT, check=True)
cp = subprocess.check_output(['clojure', '-J-Xms64m', '-J-Xmx512m', '-Spath'], cwd=REPO, text=True).strip()
cp = ':'.join(str((REPO / p).resolve()) for p in cp.split(':')) + ':' + str(ROOT / 'src')
cmd = ['java', '-Xms64m', '-Xmx512m', '-cp', cp, 'clojure.main', '-m', 'kaocha.runner', '--affected-since']
cases = [('leaf', 'test/sample/core_test.clj', '4 tests, 3 assertions', 'AFFECTED 1/2'),
         ('source', 'src/sample/core.clj', '4 tests, 3 assertions', 'AFFECTED 1/2'),
         ('deps', 'deps.edn', '5 tests, 4 assertions', 'AFFECTED FULL:'),
         ('unknown', 'notes.md', '5 tests, 4 assertions', 'AFFECTED FULL:'),
         ('tracker', None, '5 tests, 4 assertions', 'AFFECTED FULL:'),
         ('clean', None, '5 tests, 4 assertions', 'AFFECTED FULL:')]
for name, file, totals, selection in cases:
    if file:
        with open(ROOT / file, 'a') as f: f.write('\n;; witness\n')
    r = subprocess.run(cmd + [':tracker' if name == 'tracker' else 'HEAD'], cwd=ROOT,
                       stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=90)
    (REPO / 'evidence' / ('cli-' + name + '.log')).write_text(r.stdout)
    assert totals in r.stdout, (name, r.stdout[-1500:])
    assert selection in r.stdout, (name, r.stdout[-1500:])
    assert r.stdout.splitlines()[-1].startswith('RUN-RECEIPT ')
    print('PASS', name, totals, selection, flush=True)
    if file:
        if name == 'unknown': (ROOT / file).unlink()
        else:
            original = subprocess.check_output(['git', 'show', 'HEAD:' + file], cwd=ROOT)
            (ROOT / file).write_bytes(original)
