#!/usr/bin/env bash
set -euo pipefail

if (( $# > 1 )) || [[ $# == 1 && ${1-} != --artifact-path ]]; then
    printf 'Usage: %s [--artifact-path]\n' "$0" >&2
    exit 2
fi

mc_version=''
while IFS='=' read -r key value; do
    if [[ $key == mcVersion ]]; then
        mc_version=${value%$'\r'}
        break
    fi
done < gradle.properties
short_sha=$(git rev-parse --short=7 HEAD)
if [[ ! $mc_version =~ ^[0-9][0-9A-Za-z._+-]*$ || ! $short_sha =~ ^[0-9a-f]{7,40}$ ]]; then
    printf 'Invalid Minecraft version or Git commit.\n' >&2
    exit 1
fi

jar_name="SnowBall-${mc_version}-${short_sha}.jar"
artifact="snowball-server/build/libs/$jar_name"
if [[ ! -s $artifact ]]; then
    printf 'Missing build artifact: %s\n' "$artifact" >&2
    exit 1
fi

if [[ ${1-} == --artifact-path ]]; then
    printf '%s\n' "$artifact"
    exit 0
fi

: "${GITHUB_OUTPUT:?GitHub Actions output file is required}"
: "${GITHUB_RUN_NUMBER:?GitHub Actions build number is required}"
if [[ ! $GITHUB_RUN_NUMBER =~ ^[1-9][0-9]*$ ]]; then
    printf 'Invalid GitHub Actions build number.\n' >&2
    exit 1
fi

tag="${mc_version}-${GITHUB_RUN_NUMBER}-${short_sha}"
checksum=$(sha256sum -- "$artifact")
{
    printf 'tag=%s\n' "$tag"
    printf 'name=SnowBall %s\n' "$tag"
    printf 'jar=%s\n' "$jar_name"
    printf 'artifact=%s\n' "$artifact"
    printf 'sha256=%s\n' "${checksum%% *}"
    printf 'mc_version=%s\n' "$mc_version"
    printf 'short_sha=%s\n' "$short_sha"
} >> "$GITHUB_OUTPUT"
