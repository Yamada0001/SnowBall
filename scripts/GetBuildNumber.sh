#!/usr/bin/env bash
set -euo pipefail

: "${GITHUB_ENV:?GitHub Actions environment file is required}"
: "${GITHUB_RUN_NUMBER:?GitHub Actions run number is required}"
if [[ ! $GITHUB_RUN_NUMBER =~ ^[1-9][0-9]*$ ]]; then
    printf 'Invalid GitHub Actions run number.\n' >&2
    exit 1
fi

printf 'BUILD_NUMBER=%s\n' "$GITHUB_RUN_NUMBER" >> "$GITHUB_ENV"
