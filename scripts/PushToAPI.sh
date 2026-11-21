#!/usr/bin/env bash
set -eu

# SnowBall releases are created by the repository's GitHub Actions workflow.
# Do not post build information or credentials to the former upstream publisher.
printf 'This script is retired. Use the SnowBall GitHub build and release workflow.\n' >&2
exit 1
