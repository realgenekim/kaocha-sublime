#!/usr/bin/env python3
"""Recompute the pre-registered median / nearest-rank p95 without trimming."""
import math, pathlib, statistics
repo=pathlib.Path(__file__).resolve().parents[1]
def read(p):
    rows=[l.split('\t') for l in p.read_text().splitlines()]
    return {k:[float(r[2]) for r in rows if len(r)>2 and r[1]==k] for k in ['red','green']}
def stats(xs):return statistics.median(xs),sorted(xs)[math.ceil(.95*len(xs))-1]
print('| specimen | mode | baseline red median / p95 (s) | plugin red median / p95 (s) | baseline green median / p95 (s) | plugin green median / p95 (s) |')
print('|---|---|---:|---:|---:|---:|')
for s in ['mvr','ccfp']:
    b=read(repo/'evidence/baseline'/f'{s}-watch-stopwatch.tsv')
    a=read(repo/'evidence/watch'/f'{s}-watch-stopwatch.tsv')
    assert all(len(xs)==10 for d in [a,b] for xs in d.values())
    cells=[' / '.join(f'{x:.4f}' for x in stats(d[k])) for d,k in [(b,'red'),(a,'red'),(b,'green'),(a,'green')]]
    print('|',s,'| watch save → verdict |',' | '.join(cells),'|')
