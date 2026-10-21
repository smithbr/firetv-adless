# navigate: along each row with RIGHT until focus stops, then DOWN to the
# next, MAX_COLS by MAX_ROWS at most. After every move something must have
# focus, inside the screen, and a focused tile must not be cut off.
# shellcheck shell=bash

scenario_navigate() {
    local theme="$1" row col before problem moves=0
    dump
    problem="$(focus_problem)"
    if [[ -n "${problem}" ]]; then
        record "${theme}" navigate FAIL "${problem%%$'\t'*}" "at start: ${problem#*$'\t'}"
        return
    fi
    shot "${theme}" home
    for (( row = 0; row < MAX_ROWS; row++ )); do
        for (( col = 0; col < MAX_COLS; col++ )); do
            before="$(focused)"
            press RIGHT
            dump
            moves=$(( moves + 1 ))
            problem="$(focus_problem)"
            if [[ -n "${problem}" ]]; then
                record "${theme}" navigate FAIL "${problem%%$'\t'*}" "RIGHT ${col} on row ${row}: ${problem#*$'\t'}"
                shot "${theme}" navigate
                return
            fi
            [[ "$(focused)" != "${before}" ]] || break
        done
        before="$(focused)"
        press DOWN
        dump
        moves=$(( moves + 1 ))
        problem="$(focus_problem)"
        if [[ -n "${problem}" ]]; then
            record "${theme}" navigate FAIL "${problem%%$'\t'*}" "DOWN from row ${row}: ${problem#*$'\t'}"
            shot "${theme}" navigate
            return
        fi
        [[ "$(focused)" != "${before}" ]] || break
    done
    shot "${theme}" navigate
    record "${theme}" navigate PASS focus "${moves} moves, focus always on screen"
}
