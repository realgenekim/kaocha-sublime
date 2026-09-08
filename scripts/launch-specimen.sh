#!/usr/bin/env bash
set -eu
export STORE_BACKEND=jsonl CFP_MAIL_DISABLE=1
JVM_ARGS=()
if [[ "$PWD" == */ccfp ]]; then JVM_ARGS=(-J-Xmx512m); fi
exec clojure "${JVM_ARGS[@]}" -Sdeps '{:deps {local/kaocha-sublime {:local/root "/home/forge/src/kaocha-sublime"}}}' -M:run-tests --plugin kaocha.plugin/affected --plugin kaocha.plugin/run-receipt "$@"
