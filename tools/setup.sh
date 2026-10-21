#!/usr/bin/env bash
# Set up this computer for the tools: Homebrew if it is missing, then the
# Brewfile (adb), GitHub's gh while the repo is private, and the firetv command on PATH: all
# that downloading and installing the released APK needs. Then it offers to
# carry on with firetv setup, which walks through the TV. --build also sets up building the launcher here: Temurin, the
# Android SDK and its licenses, and sdk.dir in local.properties, so no
# ANDROID_HOME is needed. Safe to run again: each step skips what is already
# done. FIRETV_BIN picks where firetv is linked (default ~/.local/bin).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
source ./ui.sh

ROOT="$(cd .. && pwd)"
BIN="${FIRETV_BIN:-${HOME}/.local/bin}"

# Packages outside the Brewfile, as "KIND NAME COMMAND": KIND is formula or
# cask, and COMMAND on PATH (- for none) means it came some other way and is
# kept. Building is for developers, so these are not in the Brewfile.
BUILD_PKGS=("cask temurin -" "cask android-commandlinetools sdkmanager")

# Homebrew's brew, also when its shellenv is not in this shell yet.
find_brew() {
    local brew
    for brew in /opt/homebrew/bin/brew /usr/local/bin/brew; do
        [[ -x "${brew}" ]] && { printf '%s\n' "${brew}"; return 0; }
    done
    return 1
}

# Installs Homebrew, asking first, unless it is there; puts it on PATH for
# this run, and in the shell profile when new terminals would not find it.
setup_brew() {
    local brew on_path=true
    command -v brew >/dev/null 2>&1 || on_path=false
    if ! brew="$(command -v brew || find_brew)"; then
        printf 'Homebrew installs adb. Its installer asks for your password and may install Apple'"'"'s Command Line Tools.\n'
        confirm "Install Homebrew (https://brew.sh)?" || die "Homebrew is needed: https://brew.sh"
        /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
        brew="$(find_brew)" || die "Homebrew did not install; see https://brew.sh"
    fi
    # bash, or brew writes for the login shell, which may be fish.
    eval "$("${brew}" shellenv bash)"
    _check "Homebrew"
    [[ "${on_path}" == true ]] \
        || add_to_profile "Homebrew" "brew shellenv" "eval \"\$(${brew} shellenv)\"" "${brew} shellenv | source"
}

# Installs what the Brewfile lists, asking first, unless it is installed.
# adb that came some other way (Android Studio, say) is kept.
brew_bundle() {
    local brewfile="${ROOT}/Brewfile" missing
    if tool_installed adb || brew bundle check --file "${brewfile}" >/dev/null 2>&1; then
        _check "adb"
        return 0
    fi
    missing="$(brew bundle list --file "${brewfile}" --all 2>/dev/null | paste -sd ' ' -)"
    confirm "Install ${missing:-adb} with Homebrew (see Brewfile)?" \
        || die "firetv needs adb: brew bundle --file ${brewfile}"
    spin "Installing ${missing:-adb}" brew bundle install --file "${brewfile}"
    _check "adb installed"
}

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
    if [[ "${build}" == true ]]; then
        command -v java >/dev/null 2>&1 || _caution "Java: Temurin 17 to 27 from Adoptium's package repository"
        [[ -n "${ANDROID_HOME:-}" ]] || _caution "Android SDK: Android's command-line tools, then set ANDROID_HOME to the SDK"
    fi
}

# While the repo is private, its releases download only through gh, signed
# in to an account that can see the repo. Once it is public, curl reaches
# them and this has nothing to do.
setup_github() {
    if curl -fsIL -o /dev/null "https://github.com/${REPO}/releases/latest/download/launcher.apk" 2>/dev/null; then
        _check "Releases download without signing in"
        return 0
    fi
    case "${OSTYPE}" in
        darwin*) brew_install "formula gh gh" ;;
        *) tool_installed gh || { _caution "gh: https://github.com/cli/cli#installation, then run this again"; return 0; } ;;
    esac
    if ! gh auth status >/dev/null 2>&1; then
        log_info "${REPO} is private, so downloading the launcher needs your GitHub account."
        if ! confirm "Sign in to GitHub now?"; then
            _caution "Not signed in to GitHub: the install step cannot download the launcher"
            return 0
        fi
        gh auth login --hostname github.com --git-protocol https --web
    fi
    if gh release view --repo "${REPO}" >/dev/null 2>&1; then
        _check "GitHub: releases download with your account"
    else
        _cross "Your GitHub account cannot see ${REPO}'s releases; ask its owner for access"
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
}

# The startup file of the user's login shell, where a PATH line belongs;
# empty for a shell this does not know.
shell_profile() {
    case "${SHELL##*/}" in
        zsh)
            # Asks zsh, since ZDOTDIR may be set in its zshenv and not here.
            # shellcheck disable=SC2016  # expands in zsh
            zsh -c 'printf "%s/.zshrc\n" "${ZDOTDIR:-${HOME}}"'
            ;;
        bash)
            # Terminal on macOS opens login shells, which read .bash_profile.
            case "${OSTYPE}" in
                darwin*) printf '%s/.bash_profile\n' "${HOME}" ;;
                *) printf '%s/.bashrc\n' "${HOME}" ;;
            esac
            ;;
        fish) printf '%s/fish/config.fish\n' "${XDG_CONFIG_HOME:-${HOME}/.config}" ;;
    esac
}

# Puts WHAT on PATH for new terminals: appends a line to the shell profile,
# asking first, unless the profile already has NEEDLE. SH_LINE is for zsh
# and bash, FISH_LINE for fish.
#   add_to_profile WHAT NEEDLE SH_LINE FISH_LINE
add_to_profile() {
    local what="$1" needle="$2" line="$3" profile where tilde="~"
    profile="$(shell_profile)"
    if [[ -z "${profile}" ]]; then
        _caution "To put ${what} on PATH, add this to your shell's startup file: ${line}"
        return 0
    fi
    [[ "${profile}" != *.fish ]] || line="$4"
    where="${profile/#"${HOME}"/${tilde}}"
    if grep -qF "${needle}" "${profile}" 2>/dev/null; then
        _check "${where} puts ${what} on PATH"
        return 0
    fi
    if ! confirm "Put ${what} on PATH in ${where}, so new terminals find it?"; then
        _caution "${what} is not on PATH in new terminals"
        return 0
    fi
    mkdir -p "$(dirname "${profile}")"
    printf '\n# firetv-adless: %s on PATH\n%s\n' "${what}" "${line}" >> "${profile}"
    _check "Put ${what} on PATH in ${where}"
}

# Puts BIN on PATH in the shell profile unless it is on PATH already.
add_bin_to_path() {
    local shown
    [[ ":${PATH}:" == *":${BIN}:"* ]] && return 0
    # $HOME, not the expanded path, so the line reads the same in any account.
    shown="${BIN/#"${HOME}"/\$HOME}"
    # The needle is the part after $HOME, so ~/.local/bin and
    # ${HOME}/.local/bin in the profile match too.
    add_to_profile "firetv (${shown})" "${BIN#"${HOME}"}" \
        "export PATH=\"${shown}:\$PATH\"" "fish_add_path \"${shown}\""
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
            setup_brew
            brew_bundle
            [[ "${build}" == false ]] || brew_install "${BUILD_PKGS[@]}"
            ;;
        linux*)
            linux_hints "${build}"
            ;;
        *)
            die "Unsupported OS: ${OSTYPE}"
            ;;
    esac
    setup_github
    [[ "${build}" == false ]] || setup_sdk
    link_firetv
    add_bin_to_path

    _success "This computer is set up."
    if [[ -t 0 && -t 1 ]] && confirm "Set up the TV now?"; then
        exec "${ROOT}/tools/firetv" setup
    fi
    log_info "Run firetv setup to set up the TV, or firetv for the menu (in a new terminal if PATH just changed)."
}

main "$@"
