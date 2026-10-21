# hide: hide a favorite from its long-press menu, check it is gone, restore
# it from the hidden apps list, and check it is back after a restart.
# shellcheck shell=bash disable=SC2016  # the awk conditions are single-quoted on purpose

scenario_hide() {
    local theme="$1" label
    if ! goto '$1 == "tile"'; then
        record "${theme}" hide FAIL no-tile "could not move focus to a tile"
        return
    fi
    label="$(label_of "$(focused)")"
    if [[ -z "${label}" ]]; then
        record "${theme}" hide FAIL no-label "the focused tile has no text or description"
        return
    fi
    # The awk conditions below read the label from the environment, so any
    # quote in it is safe.
    WANT="${label}"
    export WANT
    hold_ok
    dump
    if ! has_id app_menu; then
        record "${theme}" hide FAIL no-menu "no menu after holding OK on \"${label}\""
        return
    fi
    if ! goto '$9 == "hide app"'; then
        record "${theme}" hide FAIL no-hide-item "the menu has no Hide app"
        return
    fi
    press CENTER
    dump
    if has_tile "${label}"; then
        record "${theme}" hide FAIL still-shown "\"${label}\" is still on screen after Hide app"
        shot "${theme}" hide
        return
    fi

    # The hidden apps entry: under the Settings tab in Dapple TV, on the home
    # screen (or below it) everywhere else.
    [[ "${theme}" != DAPPLE_TV ]] || goto '$1 == "tab_settings"' || true
    if ! goto '$9 == "hidden apps"'; then
        record "${theme}" hide FAIL no-hidden-entry "could not move focus to Hidden apps"
        shot "${theme}" hide
        return
    fi
    press CENTER
    dump
    if ! has_id hidden; then
        record "${theme}" hide FAIL no-hidden-list "OK on Hidden apps opened no list"
        return
    fi
    if ! goto '$9 == ENVIRON["WANT"]'; then
        record "${theme}" hide FAIL not-listed "\"${label}\" is not in the hidden apps list"
        shot "${theme}" hide
        return
    fi
    shot "${theme}" hide
    press CENTER
    dump
    if [[ -n "$(find_node '$9 == ENVIRON["WANT"]')" ]]; then
        record "${theme}" hide FAIL not-restored "OK on \"${label}\" left it in the hidden apps list"
        return
    fi
    press BACK

    # Back on the home screen after a restart, as a favorite again.
    restart || true
    goto '$1 == "tile"' || true
    if ! has_tile "${label}"; then
        record "${theme}" hide FAIL not-back "\"${label}\" is not on the home screen after unhiding"
        return
    fi
    record "${theme}" hide PASS hide "hid and restored \"${label}\""
}
