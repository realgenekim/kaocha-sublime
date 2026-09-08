#!/usr/bin/env python3
import os,pathlib,subprocess,sys,time
spec=sys.argv[1]
fx=pathlib.Path('/var/tmp/forge/kaocha-sublime-fx');root=fx/spec
f=root/'deps.edn';original=f.read_bytes()
try:
    if spec=='ccfp':f.write_bytes(original+b'\n;; affected deps witness\n')
    started=time.monotonic()
    r=subprocess.run([str(fx/'launch.sh'),'--affected-since','HEAD','unit'],cwd=root,
                     stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,timeout=600)
    out=pathlib.Path('/home/forge/src/kaocha-sublime/evidence')
    (out/(spec+'-cli-full.log')).write_text(r.stdout)
    tests=1021 if spec=='ccfp' else 579
    assert r.returncode==0,(spec,r.stdout[-2000:])
    assert f'{tests} tests,' in r.stdout and 'AFFECTED FULL:' in r.stdout
    if spec=='ccfp':assert 'deps.edn' in r.stdout
    assert r.stdout.splitlines()[-1].startswith('RUN-RECEIPT ')
    print('PASS',spec,'explicit CLI full fallback',tests,'tests, cold seconds',time.monotonic()-started)
finally:f.write_bytes(original)
