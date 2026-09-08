#!/usr/bin/env python3
"""In-place edit -> stamped reporter verdict; preserves exact original source."""
import os, pathlib, re, subprocess, sys, time
spec, relative, kind = sys.argv[1:4]
fx = pathlib.Path('/var/tmp/forge/kaocha-sublime-fx')
root = fx / spec
path = root / relative
log = fx / 'bench/logs' / (os.environ.get('WATCH_NAME', spec) + '-watch.log')
original = path.read_text()
if kind == 'identity':
    changed = original.replace('[submission-id person-id authority seal])',
                               '[submission-id person-id authority seal probe-field])')
    changed = changed.replace('(ReviewWriteProof. submission-id person-id :committee proof-seal)',
                              '(ReviewWriteProof. submission-id person-id :committee proof-seal :probe)')
    assert changed != original and changed.count('probe-field') == 1
else: changed = original + '\n;; kaocha-sublime ' + kind + ' witness\n'

def edit(frm, to, phase):
    before = len(log.read_text().splitlines())
    prefix = len(os.path.commonprefix([frm, to]))
    suffix = len(os.path.commonprefix([frm[prefix:][::-1], to[prefix:][::-1]]))
    start = max(0, prefix - 128)
    old = frm[start:len(frm)-suffix if suffix else len(frm)]
    new = to[start:len(to)-suffix if suffix else len(to)]
    assert frm.count(old) == 1, 'Edit anchor must be unique'
    t0 = float(subprocess.check_output([sys.executable, str(fx/'bench/inplace.py'), str(path), old, new], text=True))
    deadline = time.monotonic() + 240
    while time.monotonic() < deadline:
        lines = log.read_text().splitlines()[before:]
        verdicts = [l for l in lines if re.search(r'\d+ tests,\s+\d+ assertions', l)]
        markers = [l for l in lines if '\tRUN-RECEIPT ' in l]
        if verdicts and markers:
            seconds = float(verdicts[0].split('\t')[0]) - t0
            clean = re.sub(r'\x1b\[[0-9;]*m', '', verdicts[0].split('\t', 1)[1])
            selection = [l for l in lines if '\tAFFECTED ' in l]
            out = pathlib.Path('/home/forge/src/kaocha-sublime/evidence')
            (out / (spec+'-'+kind+'-'+phase+'.log')).write_text('\n'.join(lines)+'\n')
            with open(out / 'watch-witnesses.tsv', 'a') as f:
                f.write(f'{spec}\t{kind}\t{phase}\t{seconds:.6f}\t{clean}\n')
            print(spec, kind, phase, f'{seconds:.6f}', clean, *selection, sep='\n', flush=True)
            return
        time.sleep(.05)
    raise RuntimeError('NO VERDICT after '+kind+' '+phase)
try:
    edit(original, changed, 'change')
    time.sleep(1)
    edit(changed, original, 'restore')
finally:
    if path.read_text() != original:
        with open(path, 'w') as f: f.write(original); f.flush(); os.fsync(f.fileno())
