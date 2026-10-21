#!/usr/bin/env bash
# Trim every app's cache on the TV and show the storage freed. Apps keep
# their data and sign-ins; caches refill as they are used.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./adb.sh

free_kb() {
    tv shell df -k /data | tr -d '\r' | awk 'NR == 2 && $4 ~ /^[0-9]+$/ { print $4 }'
}

connect
before="$(free_kb)"
spin "Trimming app caches" adb -s "${TV}" shell pm trim-caches 999G
after="$(free_kb)"
[[ -n "${before}" && -n "${after}" ]] || die "Could not read the TV's free storage"
_success "Caches trimmed: $(( (after - before) / 1024 )) MB freed, $(( after / 1024 )) MB free."
