#!/usr/bin/env bash
# Runs ./gradlew with retries and backoff when dependency resolution is throttled
# (HTTP 429). Gradle caches what it already fetched, so each attempt gets further.
#   tools/gradle-retry.sh test -Ppanzer.loaders=neoforge
# Env: ATTEMPTS (default 6), CELERIS_MIRROR=1 to add tools/mirror.init.gradle.
set -uo pipefail
repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repo"
args=("$@")
[ "${CELERIS_MIRROR:-0}" = 1 ] && args=(--init-script "$repo/tools/mirror.init.gradle" "${args[@]}")
delay=15
for i in $(seq 1 "${ATTEMPTS:-6}"); do
  out=$(./gradlew "${args[@]}" 2>&1); code=$?
  if [ $code -eq 0 ]; then printf '%s\n' "$out"; exit 0; fi
  if ! grep -q -E 'status code 429|Too Many Requests|Could not resolve|Could not GET' <<<"$out"; then
    printf '%s\n' "$out"; exit $code   # a real build/test failure: do not retry
  fi
  echo "attempt $i throttled; retrying in ${delay}s" >&2
  sleep "$delay"; delay=$((delay * 2))
done
printf '%s\n' "$out"; exit 1
