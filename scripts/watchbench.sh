#!/usr/bin/env bash
# B: kaocha --watch benchmark. N iterations of red/green edits.
# Stopwatch = HiRes per-line stamping of the watcher's own stdout (sub-ms),
# completion detected by polling the stamped log every 50 ms.
set -uo pipefail
export PATH=$HOME/.local/bin:$HOME/bin:$PATH
FX=/var/tmp/forge/kaocha-sublime-fx/bench
SPEC="$1"; REPO="$2"; SUITE="$3"; FILE="$4"; FROM="$5"; TO="$6"; N="${7:-10}"
LOG="$FX/logs/${SPEC}-watch.log"; SW="$FX/logs/${SPEC}-watch-stopwatch.tsv"
: > "$LOG"; : > "$SW"
cd "$REPO" || exit 1
VERDICT='tests,[[:space:]]+[0-9]+[[:space:]]+assertions'

FIFO="$FX/logs/${SPEC}.fifo"
rm -f "$FIFO"; mkfifo "$FIFO"
/var/tmp/forge/kaocha-sublime-fx/launch.sh --watch $SUITE < /dev/null > "$FIFO" 2>&1 &
JPID=$!
perl "$FX/stamp.pl" < "$FIFO" >> "$LOG" &
STAMP_PID=$!
printf '%s\n' "$JPID" > "$FX/logs/${SPEC}.pid"
printf '%s\n' "$STAMP_PID" > "$FX/logs/${SPEC}-stamp.pid"
echo "WATCH_JAVA_PID=$JPID STAMP_PID=$STAMP_PID"

wait_verdict () { # from-linecount, timeout, want(red|green|any)
  local from="$1" tmo="$2" want="$3" deadline line
  deadline=$(perl -MTime::HiRes=time -e 'print time + $ARGV[0]' "$tmo")
  while :; do
    line=$(tail -n +$((from+1)) "$LOG" | grep -aE "$VERDICT" | \
      { case "$want" in
          red)   grep -avE '(^|[^0-9])0 failures' ;;
          green) grep -aE '[^0-9]0 failures\.' ;;
          *)     cat ;;
        esac; } | head -1)
    [ -n "$line" ] && { echo "$line"; return 0; }
    [ "$(perl -MTime::HiRes=time -e 'print ((time > $ARGV[0]) ? 1 : 0)' "$deadline")" = "1" ] && { echo "TIMEOUT"; return 1; }
    sleep 0.05
  done
}
record () { # iter kind verdictline t0
  local i="$1" k="$2" v="$3" t0="$4"
  if [ "$v" = "TIMEOUT" ]; then printf '%s\t%s\tTIMEOUT\t%s\t\n' "$i" "$k" "$t0" >> "$SW"; return; fi
  local ts d txt
  ts=$(printf '%s' "$v" | cut -f1)
  d=$(perl -e 'printf "%.3f", $ARGV[0]-$ARGV[1]' "$ts" "$t0")
  txt=$(printf '%s' "$v" | cut -f2- | sed 's/\x1b\[[0-9;]*m//g')
  printf '%s\t%s\t%s\t%s\t%s\n' "$i" "$k" "$d" "$t0" "$txt" >> "$SW"
}

echo "waiting for initial run..." >&2
INIT=$(wait_verdict 0 1200 any) || { echo "INITIAL TIMEOUT" >&2; kill "$JPID" "$STAMP_PID" 2>/dev/null; exit 1; }
printf 'initial\tinitial\t\t\t%s\n' "$(printf '%s' "$INIT" | cut -f2- | sed 's/\x1b\[[0-9;]*m//g')" >> "$SW"
sleep 2

for i in $(seq 1 "$N"); do
  LC=$(wc -l < "$LOG"); T0=$(python3 "$FX/inplace.py" "$FILE" "$FROM" "$TO") || exit 2
  record "$i" red "$(wait_verdict "$LC" 900 red)" "$T0"
  sleep 1
  LC=$(wc -l < "$LOG"); T0=$(python3 "$FX/inplace.py" "$FILE" "$TO" "$FROM") || exit 2
  record "$i" green "$(wait_verdict "$LC" 900 green)" "$T0"
  sleep 1
  echo "iter $i done" >&2
done

echo "WATCH_JAVA_PID=$JPID"
grep VmRSS /proc/$JPID/status | tee -a "$SW"
echo "STOPWATCH=$SW"
# Caller retains this exact JVM for the fallback/closure witnesses, then kills it.
