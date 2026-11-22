#!/usr/bin/env bash
# Run from the patch directory, using Bash and GNU mv (including Git Bash).

export LC_ALL=C

fail() {
  printf 'ShiftPatches: %s\n' "$*" >&2
  exit 1
}

if [[ $# != 1 || ! $1 =~ ^[+-]?[0-9]+$ ]]; then
  printf 'Usage: bash %s <signed decimal shift>\n' "$0" >&2
  exit 2
fi

# Limit normalized values to 18 digits so signed Bash arithmetic cannot wrap.
max_number=999999999999999999
read_decimal() {
  local digits=$1
  digits=${digits#"${digits%%[!0]*}"}
  digits=${digits:-0}
  (( ${#digits} <= 18 )) || fail "Number out of range: $1"
  decimal=$((10#$digits))
}

read_decimal "${1#[+-]}"
shift_by=$decimal
if [[ $1 == -* ]]; then
  shift_by=$((-shift_by))
fi

exists() {
  [[ -e $1 || -L $1 ]]
}

shopt -s nullglob
sources=()
destinations=()
for file in *.patch; do
  [[ $file =~ ^([0-9]+)(.*)$ ]] || continue
  number=${BASH_REMATCH[1]}
  suffix=${BASH_REMATCH[2]}
  [[ -f $file && ! -L $file ]] || fail "Not a regular patch file: $file"

  read_decimal "$number"
  next=$((decimal + shift_by))
  (( next >= 0 && next <= max_number )) || fail "Result out of range: $file"
  printf -v destination '%0*d%s' "${#number}" "$next" "$suffix"
  [[ $destination != "$file" ]] || continue

  for planned in "${destinations[@]}"; do
    [[ $destination != "$planned" ]] || fail "Duplicate destination: $destination"
  done
  sources+=("$file")
  destinations+=("$destination")
done

for destination in "${destinations[@]}"; do
  exists "$destination" || continue
  moving=false
  for file in "${sources[@]}"; do
    if [[ $destination == "$file" ]]; then
      moving=true
      break
    fi
  done
  $moving || fail "Destination already exists: $destination"
done

count=${#sources[@]}
(( count > 0 )) || exit 0

# A private directory on the same filesystem holds each file by its original name.
stage=$(mktemp -d -- ./.shift-patches.XXXXXXXX) || fail 'Could not create temporary patch directory'
staged=0
placed=0
interrupted=false

move_patch() {
  # -n can report success for a skipped move; verify that the source is gone.
  mv -nT -- "$1" "$2" && ! exists "$1"
}

cleanup() {
  local status=$? i
  trap - EXIT
  trap '' HUP INT TERM
  if (( status != 0 )); then
    # Clear final names first: they may also be another patch's original name.
    for ((i = placed - 1; i >= 0; i--)); do
      move_patch "./${destinations[i]}" "$stage/${sources[i]}" ||
        printf 'ShiftPatches: could not restage %s\n' "${destinations[i]}" >&2
    done
    for ((i = staged - 1; i >= 0; i--)); do
      exists "$stage/${sources[i]}" || continue
      move_patch "$stage/${sources[i]}" "./${sources[i]}" ||
        printf 'ShiftPatches: could not restore %s\n' "${sources[i]}" >&2
    done
  fi
  # Never remove remaining patches, even if rollback could not finish.
  if ! rmdir -- "$stage"; then
    printf 'ShiftPatches: recovery files remain in %s\n' "$stage" >&2
    status=1
  fi
  exit "$status"
}

trap cleanup EXIT
# Let the current move finish and record it before rolling back.
trap 'interrupted=true' HUP INT TERM
for ((i = 0; i < count; i++)); do
  move_patch "./${sources[i]}" "$stage/${sources[i]}" || fail "Could not stage ${sources[i]}"
  ((staged += 1))
  $interrupted && fail 'Interrupted'
done
for ((i = 0; i < count; i++)); do
  move_patch "$stage/${sources[i]}" "./${destinations[i]}" || fail "Could not place ${destinations[i]}"
  ((placed += 1))
  $interrupted && fail 'Interrupted'
done
exit 0
