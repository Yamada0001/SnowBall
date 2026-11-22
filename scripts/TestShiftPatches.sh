#!/usr/bin/env bash
set -euo pipefail

script=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/ShiftPatches.sh
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT

fixture() {
  mkdir -- "$work/$1"
  cd -- "$work/$1"
}

reject_shift() {
  if bash "$script" "$1" >stdout 2>stderr; then
    printf 'Unexpected success for shift %s in %s\n' "$1" "$PWD" >&2
    exit 1
  fi
  [[ -s stderr ]]
  local -a stages
  shopt -s nullglob
  stages=(.shift-patches.*)
  [[ ${#stages[@]} == 0 ]]
}

fixture overlap
printf 'A\n' > 0001-same.patch
printf 'B\n' > 0002-same.patch
printf 'Leave me alone\n' > unnumbered.patch
bash "$script" +1
[[ ! -e 0001-same.patch ]]
[[ $(<0002-same.patch) == A && $(<0003-same.patch) == B ]]
bash "$script" -1
[[ ! -e 0003-same.patch ]]
[[ $(<0001-same.patch) == A && $(<0002-same.patch) == B ]]
[[ $(<unnumbered.patch) == 'Leave me alone' ]]
bash "$script" +0
[[ $(<0001-same.patch) == A && $(<0002-same.patch) == B ]]

fixture decimal
printf 'Padded\n' > '0008-with space.patch'
printf 'Widened\n' > 99-last.patch
bash "$script" +08
[[ $(<'0016-with space.patch') == Padded && $(<107-last.patch) == Widened ]]

fixture duplicate
printf 'A\n' > 9-same.patch
printf 'B\n' > 09-same.patch
reject_shift 1
[[ $(<9-same.patch) == A && $(<09-same.patch) == B && ! -e 10-same.patch ]]

fixture occupied
printf 'A\n' > 0001-same.patch
mkdir 0002-same.patch
reject_shift 1
[[ $(<0001-same.patch) == A && -d 0002-same.patch ]]

fixture invalid
printf 'A\n' > 0001-same.patch
printf 'B\n' > 0003-same.patch
reject_shift -2
reject_shift 1+1
reject_shift 999999999999999999999999
[[ $(<0001-same.patch) == A && $(<0003-same.patch) == B && ! -e 0002-same.patch ]]
printf 'C\n' > 999999999999999999-overflow.patch
reject_shift 1
[[ $(<0001-same.patch) == A && $(<999999999999999999-overflow.patch) == C ]]

fixture late_collision
printf 'A\n' > 0001-same.patch
printf 'B\n' > 0002-same.patch
(
  mv() {
    # Simulate a file arriving at the second destination after preflight.
    if [[ $3 == ./.shift-patches.*/0002-same.patch && $4 == ./0003-same.patch ]]; then
      printf 'Arrived late\n' > "$4"
    fi
    command mv "$@"
  }
  export -f mv
  reject_shift 1
)
[[ $(<0001-same.patch) == A && $(<0002-same.patch) == B ]]
[[ $(<0003-same.patch) == 'Arrived late' ]]

fixture empty
bash "$script" 1

printf 'ShiftPatches regression tests passed.\n'
