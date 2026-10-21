#!/usr/bin/env bash
# Copies each theme's home screen from the last run (tests/device/out/
# <THEME>/home.png) to docs/screenshots/, 1280 pixels wide, and rewrites the
# table between the screenshot markers in the README. Run it after a fully
# passing run.sh, so the README never shows a failing build.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

[[ -s "${RESULTS}" ]] || die "no results in ${RESULTS}: run tests/device/run.sh first"
[[ ! -s "${OUT}/failures.txt" ]] || die "the last run failed ($(wc -l < "${OUT}/failures.txt" | tr -d ' ') checks); publish only from a passing run"

SHOTS="${ROOT}/docs/screenshots"
README="${ROOT}/README.md"
mkdir -p "${SHOTS}"

cells=()
while read -r theme; do
    source_png="${OUT}/${theme}/home.png"
    [[ -f "${source_png}" ]] || die "no ${source_png}: the last run skipped ${theme}"
    slug="$(tr '[:upper:]_' '[:lower:]-' <<< "${theme}")"
    sips --resampleWidth 1280 "${source_png}" --out "${SHOTS}/${slug}.png" >/dev/null
    label="$(sed -nE "s/^    ${theme}\\(\"([^\"]+)\".*/\\1/p" "${ROOT}/app/src/main/java/la/bsmith/launcher/Themes.kt")"
    cells+=("![${label}](docs/screenshots/${slug}.png)<br>${label}")
done < <(themes)

# Two screenshots per row.
table="$(
    printf '| | |\n| --- | --- |\n'
    for (( i = 0; i < ${#cells[@]}; i += 2 )); do
        printf '| %s | %s |\n' "${cells[i]}" "${cells[i + 1]:-}"
    done
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
