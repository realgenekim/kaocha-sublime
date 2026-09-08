#!/usr/bin/env python3
# IN-PLACE save (truncate + write to the SAME inode), the way an editor saves.
# Deliberately NOT sed -i / perl -i, which write a temp file and rename: kaocha's
# watcher was measured blind to rename-saves (see findings).
import sys, os, time
path, frm, to = sys.argv[1], sys.argv[2], sys.argv[3]
s = open(path).read()
n = s.count(frm)
if n != 1:
    sys.stderr.write("EDIT-REFUSED: %d occurrences of %r in %s\n" % (n, frm, path)); sys.exit(1)
s = s.replace(frm, to)
fd = os.open(path, os.O_WRONLY | os.O_TRUNC)
os.write(fd, s.encode()); os.fsync(fd); os.close(fd)
sys.stdout.write("%.6f\n" % time.time())
