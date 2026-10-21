# perf: the bench.sh run on the devicetest build. 24 moves (3 one way, 3
# back, four times, 450 ms apart) from Monkey, then dumpsys gfxinfo. Fails
# when the 90th or 99th percentile frame time goes over the theme's limits in
# baseline.tsv; skips, printing the numbers, when the theme has none. Janky %
# is printed only: Monkey's queued presses inflate it.
# shellcheck shell=bash disable=SC2016  # the awk conditions are single-quoted on purpose

BASELINE="${DEVICE_DIR}/baseline.tsv"

# Folds a run's perf.tsv into BASELINE, which keeps per theme the limits and
# the worst p90 and p99 seen in any calibrating run. The limits: p90 the
# worst plus a quarter and 5 ms, and at least 15 ms over it (runs differ by 5
# to 10 ms); p99 the worst plus half.
merge_baseline() {
    local merged
    merged="$(awk -F'\t' '
        FNR == NR { if ($0 !~ /^#/ && NF >= 5) { w90[$1] = $4; w99[$1] = $5; order[++n] = $1 } next }
        {
            if (!($1 in w90)) order[++n] = $1
            if ($4 > w90[$1]) w90[$1] = $4
            if ($5 > w99[$1]) w99[$1] = $5
        }
        END {
            print "# theme\tmax p90 ms\tmax p99 ms\tworst p90 seen\tworst p99 seen (tests/device/run.sh --baseline)"
            for (i = 1; i <= n; i++) {
                t = order[i]
                a = int(w90[t] * 1.25 + 0.999) + 5
                if (a < w90[t] + 15) a = w90[t] + 15
                printf "%s\t%d\t%d\t%d\t%d\n", t, a, int(w99[t] * 1.5 + 0.999), w90[t], w99[t]
            }
        }' "$( [[ -f "${BASELINE}" ]] && echo "${BASELINE}" || echo /dev/null )" "$1")"
    printf '%s\n' "${merged}" > "${BASELINE}"
}

scenario_perf() {
    local theme="$1" there=RIGHT back=LEFT stats total jank p90 p99 limits
    # Terminal is one vertical list.
    [[ "${theme}" != TERMINAL ]] || { there=DOWN; back=UP; }
    goto '$1 == "tile"' || true
    tv shell dumpsys gfxinfo "${PKG}" reset >/dev/null
    {
        local _round _move
        for _round in 1 2 3 4; do
            for _move in 1 2 3; do printf 'DispatchPress(KEYCODE_DPAD_%s)\nUserWait(450)\n' "${there}"; done
            for _move in 1 2 3; do printf 'DispatchPress(KEYCODE_DPAD_%s)\nUserWait(450)\n' "${back}"; done
        done
    } | monkey_run
    sleep 1.5
    stats="$(tv shell dumpsys gfxinfo "${PKG}" | tr -d '\r')"
    total="$(sed -nE 's/^Total frames rendered: ([0-9]+).*/\1/p' <<< "${stats}" | head -1)"
    jank="$(sed -nE 's/^Janky frames: [0-9]+ \(([0-9.]+)%\).*/\1/p' <<< "${stats}" | head -1)"
    p90="$(sed -nE 's/^90th percentile: ([0-9]+)ms.*/\1/p' <<< "${stats}" | head -1)"
    p99="$(sed -nE 's/^99th percentile: ([0-9]+)ms.*/\1/p' <<< "${stats}" | head -1)"
    shot "${theme}" perf
    if [[ -z "${total}" || -z "${p90}" || -z "${p99}" ]]; then
        record "${theme}" perf FAIL no-stats "dumpsys gfxinfo gave no frame statistics"
        return
    fi
    printf '%s\t%s\t%s\t%s\t%s\n' "${theme}" "${total}" "${jank:-?}" "${p90}" "${p99}" >> "${OUT}/perf.tsv"
    local measured="${total} frames, p90 ${p90} ms, p99 ${p99} ms (${jank:-?}% janky)"
    limits=""
    [[ ! -f "${BASELINE}" ]] || limits="$(awk -F'\t' -v t="${theme}" '$1 == t { print $2 "\t" $3 }' "${BASELINE}")"
    if [[ -z "${limits}" ]]; then
        record "${theme}" perf SKIP baseline "no baseline; ${measured}"
    elif awk -v a="${p90}" -v b="${p99}" -v l="${limits}" 'BEGIN { split(l, m, "\t"); exit !(a <= m[1] && b <= m[2]) }'; then
        record "${theme}" perf PASS frames "${measured}; limits ${limits/$'\t'/ and } ms"
    else
        record "${theme}" perf FAIL frames "${measured}; over the limits ${limits/$'\t'/ and } ms"
    fi
}
