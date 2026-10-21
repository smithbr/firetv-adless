#!/usr/bin/env bash
# Set up this computer for the tools: adb, gum, and the firetv command on
# PATH: all that installing the released APK needs. --build also sets up
# building the launcher here: Temurin, the Android SDK and its licenses, and
# sdk.dir in local.properties, so no ANDROID_HOME is needed. Safe to run
# again: each step skips what is already done. FIRETV_BIN picks where
# firetv is linked (default ~/.local/bin).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./ui.sh

ROOT="$(cd .. && pwd)"
BIN="${FIRETV_BIN:-${HOME}/.local/bin}"

# Homebrew packages, as "KIND NAME COMMAND": KIND is formula or cask, and
# COMMAND on PATH (- for none) means it came some other way and is kept.
RUN_PKGS=("formula gum gum" "cask android-platform-tools adb")
BUILD_PKGS=("cask temurin -" "cask android-commandlinetools sdkmanager")

# Installs each "KIND NAME COMMAND" not installed yet.
brew_install() {
    local entry kind name cmd
    for entry in "$@"; do
        read -r kind name cmd <<< "${entry}"
        if [[ "${cmd}" != - ]] && tool_installed "${cmd}"; then
            _check "${name}"
        elif brew list "--${kind}" "${name}" >/dev/null 2>&1; then
            _check "${name}"
        else
            spin "Installing ${name}" brew install "--${kind}" "${name}"
            _check "${name} installed"
        fi
    done
}

# Prints how to install what Linux lacks; there is no one package manager
# to install it with.
linux_hints() {
    local build="$1"
    tool_installed adb || _caution "adb: sudo apt install adb (or Android's Platform-Tools)"
    tool_installed gum || _caution "gum: https://github.com/charmbracelet/gum#installation"
    if [[ "${build}" == true ]]; then
        command -v java >/dev/null 2>&1 || _caution "Java: Temurin 17 to 27 from Adoptium's package repository"
        [[ -n "${ANDROID_HOME:-}" ]] || _caution "Android SDK: Android's command-line tools, then set ANDROID_HOME to the SDK"
    fi
}

# The Android SDK directory: Homebrew's on macOS, ANDROID_HOME elsewhere.
sdk_dir() {
    case "${OSTYPE}" in
        darwin*) printf '%s/share/android-commandlinetools\n' "$(brew --prefix)" ;;
        *) printf '%s\n' "${ANDROID_HOME:-}" ;;
    esac
}

# Accepts the SDK licenses unless they already are, and points Gradle at the
# SDK through local.properties (git ignores it).
setup_sdk() {
    local sdk sdkmanager props="${ROOT}/local.properties" tmp
    sdk="$(sdk_dir)"
    [[ -n "${sdk}" && -d "${sdk}" ]] || { _cross "Android SDK not found; set ANDROID_HOME and run this again"; return 0; }

    if [[ -f "${sdk}/licenses/android-sdk-license" ]]; then
        _check "SDK licenses accepted"
    else
        sdkmanager="$(command -v sdkmanager || printf '%s/cmdline-tools/latest/bin/sdkmanager' "${sdk}")"
        # sh, not this shell: pipefail would fail on yes's broken pipe.
        # shellcheck disable=SC2016 # $1 and $2 expand in that sh.
        spin "Accepting the SDK licenses" sh -c 'yes | "$1" --sdk_root="$2" --licenses >/dev/null' _ "${sdkmanager}" "${sdk}"
        _check "SDK licenses accepted"
    fi

    if grep -qxF "sdk.dir=${sdk}" "${props}" 2>/dev/null; then
        _check "local.properties points at the SDK"
    else
        tmp="$(mktemp "${TMPDIR:-/tmp}/local.properties.XXXXXX")"
        { grep -v '^sdk\.dir=' "${props}" 2>/dev/null || true; printf 'sdk.dir=%s\n' "${sdk}"; } > "${tmp}"
        mv "${tmp}" "${props}"
        _check "local.properties points at ${sdk}"
    fi
}

# Links tools/firetv into ~/.local/bin, leaving alone anything else there
# under that name.
link_firetv() {
    local target="${ROOT}/tools/firetv" link="${BIN}/firetv"
    mkdir -p "${BIN}"
    if [[ "$(readlink "${link}" 2>/dev/null)" == "${target}" ]]; then
        _check "firetv linked in ${BIN}"
    elif [[ -e "${link}" || -L "${link}" ]]; then
        _caution "${link} already exists and is not this repo's; left it alone"
    else
        ln -s "${target}" "${link}"
        _check "firetv linked in ${BIN}"
    fi
    [[ ":${PATH}:" == *":${BIN}:"* ]] || _caution "${BIN} is not on PATH; add it to run firetv from anywhere"
}

main() {
    local build=false
    case "${1:-}" in
        --build) build=true ;;
        "") ;;
        *) die "Usage: tools/setup.sh [--build]" ;;
    esac

    _header "Setting up for firetv"
    case "${OSTYPE}" in
        darwin*)
            command -v brew >/dev/null 2>&1 || die "Homebrew not found: https://brew.sh"
            brew_install "${RUN_PKGS[@]}"
            [[ "${build}" == false ]] || brew_install "${BUILD_PKGS[@]}"
            ;;
        linux*)
            linux_hints "${build}"
            ;;
        *)
            die "Unsupported OS: ${OSTYPE}"
            ;;
    esac
    [[ "${build}" == false ]] || setup_sdk
    link_firetv

    _success "Set up. Run firetv to connect to the TV and install the launcher."
}

main "$@"
