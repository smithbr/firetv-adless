# longpress: hold OK on a tile as a remote does (repeats, then the release).
# The menu must open and stay open: the release must not pick its first
# item, and must not open the app. Back then closes it.
# shellcheck shell=bash disable=SC2016  # the awk conditions are single-quoted on purpose

scenario_longpress() {
    local theme="$1" label
    if ! goto '$1 == "tile"'; then
        record "${theme}" longpress FAIL no-tile "could not move focus to a tile"
        return
    fi
    label="$(label_of "$(focused)")"
    if [[ -z "${label}" ]]; then
        record "${theme}" longpress FAIL no-label "the focused tile has no text or description"
        return
    fi
    hold_ok
    if ! in_front; then
        record "${theme}" longpress FAIL opened-app "holding OK on \"${label}\" opened the app"
        return
    fi
    dump
    if ! has_id app_menu; then
        record "${theme}" longpress FAIL no-menu "no menu after holding OK on \"${label}\": it never opened, or the release picked an item and closed it"
        shot "${theme}" longpress
        return
    fi
    shot "${theme}" longpress
    press BACK
    dump
    if has_id app_menu; then
        record "${theme}" longpress FAIL back "Back left the menu open"
        return
    fi
    record "${theme}" longpress PASS menu "held OK on \"${label}\": menu open, nothing picked"
}
