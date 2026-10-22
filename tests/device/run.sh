#!/usr/bin/env bash
# Builds the devicetest variant, installs it beside the launcher, runs every
# scenario in every theme on the TV, and prints a pass/fail table. Holds the
# TV lock (tools/adb.sh) throughout. Exits 1 when anything failed.
#
#   run.sh [--no-build] [--themes TERMINAL,AMBIENT] [--scenarios navigate,hide] [--baseline]
#
# --baseline adds this run's frame timings to baseline.tsv (see merge_baseline).
# Results, screenshots and timings go to tests/device/out/: results.tsv,
# failures.txt (one theme/scenario/check per failure), <theme>/<scenario>.png.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
for scenario_file in "${DEVICE_DIR}"/scenarios/*.sh; do
    # shellcheck source=/dev/null
    source "${scenario_file}"
done

BUILD=1
WRITE_BASELINE=0
SELECTED_THEMES="$(themes | paste -sd, -)"
SELECTED_SCENARIOS="navigate,longpress,hide,receiver,perf"
while (( $# )); do
    case "$1" in
        --no-build) BUILD=0 ;;
        --baseline) WRITE_BASELINE=1 ;;
        --themes) SELECTED_THEMES="${2:?--themes needs a list}"; shift ;;
        --scenarios) SELECTED_SCENARIOS="${2:?--scenarios needs a list}"; shift ;;
        -h|--help) sed -n '2,10s/^# \{0,1\}//p' "$0"; exit 0 ;;
        *) die "unknown option: $1 (see --help)" ;;
    esac
    shift
done
IFS=, read -ra THEME_LIST <<< "${SELECTED_THEMES}"
IFS=, read -ra SCENARIO_LIST <<< "${SELECTED_SCENARIOS}"
for scenario in "${SCENARIO_LIST[@]}"; do
    declare -F "scenario_${scenario}" >/dev/null || die "no scenario named ${scenario}"
done

if (( BUILD )); then
    (cd "${ROOT}" && ./gradlew --console=plain --quiet :app:assembleDevicetest)
fi
[[ -f "${APK}" ]] || die "no ${APK}: run without --no-build"

connect
rm -rf "${OUT}"
mkdir -p "${OUT}"
: > "${RESULTS}"
tv install -r "${APK}" | tr -d '\r' | grep -v '^Performing' || true

started=${SECONDS}
for theme in "${THEME_LIST[@]}"; do
    for scenario in "${SCENARIO_LIST[@]}"; do
        printf '%-12s %-10s ' "${theme}" "${scenario}" >&2
        t=${SECONDS}
        if launch "${theme}"; then
            # In a subshell with its own errexit, so a scenario that breaks
            # is recorded and the run goes on.
            set +e
            ( set -e; "scenario_${scenario}" "${theme}" )
            status=$?
            set -e
            (( status == 0 )) || record "${theme}" "${scenario}" FAIL crashed "the scenario stopped with exit ${status}"
        else
            record "${theme}" "${scenario}" FAIL launch "nothing had focus 10 s after starting"
        fi
        printf '%s (%ds)\n' "$(awk -F'\t' -v t="${theme}" -v s="${scenario}" '$1 == t && $2 == s { r = $3 } END { print r }' "${RESULTS}")" $(( SECONDS - t )) >&2
    done
done
# Leave the TV on its own home screen.
tv shell am force-stop "${PKG}"
tv shell input keyevent KEYCODE_HOME >/dev/null

if (( WRITE_BASELINE )) && [[ -s "${OUT}/perf.tsv" ]]; then
    merge_baseline "${OUT}/perf.tsv"
    printf 'Updated %s\n' "${BASELINE#"${ROOT}"/}" >&2
fi

# The table: a theme per row, a scenario per column; a cell fails when any of its checks did.
printf '\n'
awk -F'\t' -v themes="${SELECTED_THEMES}" -v scenarios="${SELECTED_SCENARIOS}" '
    { cell = $1 SUBSEP $2; if ($3 == "FAIL" || r[cell] == "") r[cell] = (r[cell] == "FAIL" ? "FAIL" : $3) }
    END {
        nt = split(themes, t, ","); ns = split(scenarios, s, ",")
        printf "%-12s", "theme"; for (j = 1; j <= ns; j++) printf "  %-9s", s[j]; printf "\n"
        for (i = 1; i <= nt; i++) {
            printf "%-12s", t[i]
            for (j = 1; j <= ns; j++) printf "  %-9s", (r[t[i], s[j]] == "" ? "-" : r[t[i], s[j]])
            printf "\n"
        }
    }' "${RESULTS}"

awk -F'\t' '$3 == "FAIL" { print $1 "/" $2 "/" $4 }' "${RESULTS}" | sort -u > "${OUT}/failures.txt"
passed="$(awk -F'\t' '$3 == "PASS"' "${RESULTS}" | wc -l | tr -d ' ')"
failed="$(awk -F'\t' '$3 == "FAIL"' "${RESULTS}" | wc -l | tr -d ' ')"
skipped="$(awk -F'\t' '$3 == "SKIP"' "${RESULTS}" | wc -l | tr -d ' ')"
if (( failed )); then
    printf '\nFailures:\n'
    awk -F'\t' '$3 == "FAIL" { printf "  %s/%s/%s: %s\n", $1, $2, $4, $5 }' "${RESULTS}"
fi
if (( skipped )); then
    printf '\nSkipped:\n'
    awk -F'\t' '$3 == "SKIP" { printf "  %s/%s/%s: %s\n", $1, $2, $4, $5 }' "${RESULTS}"
fi
printf '\n%s passed, %s failed, %s skipped in %ds. Screenshots: %s/\n' "${passed}" "${failed}" "${skipped}" $(( SECONDS - started )) "${OUT#"${ROOT}"/}"
(( failed == 0 ))
