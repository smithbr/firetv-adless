#!/usr/bin/env bash
# Copies each theme's home screen from the last run (tests/device/out/
# <THEME>/home.png) to docs/screenshots/, 1280 pixels wide, and rewrites the
# table between the screenshot markers in the README, with the Light shots
# record-gifs.sh took after their themes. Run it after a fully passing
# run.sh, so the README never shows a failing build.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

[[ -s "${RESULTS}" ]] || die "no results in ${RESULTS}: run tests/device/run.sh first"
[[ -f "${OUT}/failures.txt" ]] || die "the last run did not finish; publish only from a passing run"
[[ ! -s "${OUT}/failures.txt" ]] || die "the last run failed ($(wc -l < "${OUT}/failures.txt" | tr -d ' ') checks); publish only from a passing run"

SHOTS="${ROOT}/docs/screenshots"
README="${ROOT}/README.md"
mkdir -p "${SHOTS}"

# Two screenshots per row. A theme with a Light shot from record-gifs.sh
# takes a row of its own, Dark beside Light; the rest pair up in order.
rows=()
pending=""
while read -r theme; do
    source_png="${OUT}/${theme}/home.png"
    [[ -f "${source_png}" ]] || die "no ${source_png}: the last run skipped ${theme}"
    slug="$(tr '[:upper:]_' '[:lower:]-' <<< "${theme}")"
    sips --resampleWidth 1280 "${source_png}" --out "${SHOTS}/${slug}.png" >/dev/null
    label="$(sed -nE "s/^    ${theme}\\(\"([^\"]+)\".*/\\1/p" "${ROOT}/app/src/main/java/la/bsmith/launcher/Themes.kt")"
    cell="![${label}](docs/screenshots/${slug}.png)<br>${label}"
    if [[ -f "${SHOTS}/${slug}-light.png" ]]; then
        rows+=("| ${cell} | ![${label}, Light](docs/screenshots/${slug}-light.png)<br>${label}, Light |")
    elif [[ -z "${pending}" ]]; then
        pending="${cell}"
    else
        rows+=("| ${pending} | ${cell} |")
        pending=""
    fi
done < <(themes)
[[ -z "${pending}" ]] || rows+=("| ${pending} | |")

table="$(
    printf '| | |\n| --- | --- |\n'
    printf '%s\n' "${rows[@]}"
)"
TABLE="${table}" awk '
    /^<!-- screenshots -->$/ { print; print ""; print ENVIRON["TABLE"]; print ""; print "<!-- /screenshots -->"; skip = 1; next }
    skip && /^<!-- \/screenshots -->$/ { skip = 0; next }
    # First run: no end marker yet, so stop at the next heading.
    skip && /^## / { skip = 0; print "" }
    !skip { print }
' "${README}" > "${README}.new"
mv "${README}.new" "${README}"
printf 'Updated %s and README.md\n' "${SHOTS#"${ROOT}"/}/"
