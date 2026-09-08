#!/usr/bin/env python3
"""Stop only recorded JVM/stamper PIDs after checking command and worktree."""
import os, pathlib, signal, sys
name, specimen = sys.argv[1:3]
fx = pathlib.Path('/var/tmp/forge/kaocha-sublime-fx')
for suffix, executable in [('.pid', b'java'), ('-stamp.pid', b'perl')]:
    pid = int((fx/'bench/logs'/(name+suffix)).read_text())
    proc = pathlib.Path('/proc') / str(pid)
    if not proc.exists(): continue
    assert (proc/'cwd').resolve() == fx/specimen, (pid, 'unexpected cwd')
    args = (proc/'cmdline').read_bytes().split(b'\0')
    assert executable in args[0], (pid, args[0])
    os.kill(pid, signal.SIGTERM)
    print('Stopped', pid, args[0].decode())
