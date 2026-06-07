#!/usr/bin/env bash
set -euo pipefail

max_attempts="${GRADLE_RETRY_ATTEMPTS:-3}"
delay_seconds="${GRADLE_RETRY_DELAY_SECONDS:-20}"
attempt=1

while true; do
  echo "::group::Gradle attempt ${attempt}/${max_attempts}"
  set +e
  ./gradlew "$@"
  status=$?
  set -e
  echo "::endgroup::"

  if [[ "${status}" -eq 0 ]]; then
    exit 0
  fi

  if [[ "${attempt}" -ge "${max_attempts}" ]]; then
    echo "Gradle failed after ${max_attempts} attempts." >&2
    exit "${status}"
  fi

  echo "Gradle failed with exit code ${status}; retrying in ${delay_seconds}s." >&2
  sleep "${delay_seconds}"
  attempt=$((attempt + 1))
  delay_seconds=$((delay_seconds * 2))
done
