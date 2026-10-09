#!/usr/bin/env bash
# Copyright 2026 Netflix, Inc.
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
# in compliance with the License. You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software distributed under the License
# is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
# or implied. See the License for the specific language governing permissions and limitations under
# the License.

set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
output="${1:-$root/../com.netflix.tools.launcher/META-INF/com.netflix.tools.launcher}"

provision_zig() {
    local version=0.15.2
    local operating_system
    local architecture
    local platform
    local executable=zig

    operating_system="$(uname -s)"
    architecture="$(uname -m)"
    case "$architecture" in
        x86_64|amd64) architecture=x86_64 ;;
        arm64|aarch64) architecture=aarch64 ;;
        *) printf 'Unsupported Zig host architecture: %s\n' "$architecture" >&2; return 1 ;;
    esac
    case "$operating_system" in
        Linux)
            platform="$architecture-linux"
            ;;
        Darwin)
            platform="$architecture-macos"
            ;;
        MINGW*|MSYS*|CYGWIN*)
            platform="$architecture-windows"
            executable=zig.exe
            ;;
        *) printf 'Unsupported Zig host operating system: %s\n' "$operating_system" >&2; return 1 ;;
    esac

    local cache_root
    if [[ "$platform" == *-windows ]]; then
        cache_root="${LOCALAPPDATA:-$HOME/AppData/Local}/com.netflix.tools.launcher/zig"
    else
        cache_root="${XDG_CACHE_HOME:-$HOME/.cache}/com.netflix.tools.launcher/zig"
    fi
    local installation="$cache_root/$version/$platform"
    if [[ -x "$installation/$executable" ]]; then
        printf '%s\n' "$installation/$executable"
        return
    fi

    if ! command -v jq >/dev/null; then
        printf 'jq is required to provision Zig\n' >&2
        return 1
    fi
    mkdir -p "$cache_root"
    local index="$cache_root/download-index.json"
    if [[ ! -s "$index" ]] || ! jq -e \
            --arg version "$version" --arg platform "$platform" \
            '.[$version][$platform].tarball and .[$version][$platform].shasum' \
            "$index" >/dev/null; then
        local index_download="$cache_root/download-index.json.tmp.$$"
        curl -q --fail --silent --show-error --location \
            --output "$index_download" https://ziglang.org/download/index.json
        mv "$index_download" "$index"
    fi

    local url
    local checksum
    url="$(jq -er --arg version "$version" --arg platform "$platform" \
        '.[$version][$platform].tarball' "$index")"
    checksum="$(jq -er --arg version "$version" --arg platform "$platform" \
        '.[$version][$platform].shasum' "$index")"
    local work
    local archive
    local actual
    work="$(mktemp -d "${TMPDIR:-/tmp}/com.netflix.tools.launcher.zig.XXXXXX")"
    archive="$work/${url##*/}"
    curl -q --fail --silent --show-error --location \
        --output "$archive" "$url"
    if command -v sha256sum >/dev/null; then
        actual="$(sha256sum "$archive")"
    else
        actual="$(shasum -a 256 "$archive")"
    fi
    actual="${actual%% *}"
    if [[ "$actual" != "$checksum" ]]; then
        printf 'Invalid Zig archive checksum: %s\n' "$actual" >&2
        rm -rf "$work"
        return 1
    fi

    mkdir -p "$work/installation"
    if [[ "$archive" == *.zip ]]; then
        unzip -q "$archive" -d "$work/unpacked"
        cp -R "$work/unpacked/zig-$platform-$version/." "$work/installation/"
    else
        tar --extract --xz --strip-components=1 \
            --file "$archive" --directory "$work/installation"
    fi
    mkdir -p "$(dirname "$installation")"
    rm -rf "$installation"
    mv "$work/installation" "$installation"
    rm -rf "$work"
    printf '%s\n' "$installation/$executable"
}

if [[ -n "${ZIG:-}" ]]; then
    zig="$ZIG"
elif command -v zig >/dev/null; then
    zig="$(command -v zig)"
else
    zig="$(provision_zig)"
fi

temporary="$(mktemp -d "${TMPDIR:-/tmp}/com.netflix.tools.launcher.XXXXXX")"
trap 'rm -rf "$temporary"' EXIT

readonly c_flags=(-std=c17 -Wall -Wextra -Wpedantic -Werror)

compile_unix() {
    local classifier="$1"
    local target="$2"
    local extra_linker="$3"
    local platform="$temporary/$classifier"

    mkdir -p "$platform"
    local arguments=(
        cc -target "$target" "${c_flags[@]}" -Os -s
        -I"$root/include" -I"$root/include/darwin"
        -o "$platform/launcher"
        "$root/main.c"
    )
    if [[ -n "$extra_linker" ]]; then
        arguments+=("$extra_linker")
    fi
    "$zig" "${arguments[@]}"
    "$zig" cc -target "$target" "${c_flags[@]}" -Os -s \
        -o "$platform/dispatcher" "$root/dispatcher.c"
}

compile_windows() {
    local classifier="$1"
    local target="$2"
    local platform="$temporary/$classifier"

    mkdir -p "$platform"
    "$zig" cc -target "$target" "${c_flags[@]}" -Os -s \
        -I"$root/include" -I"$root/include/windows" \
        -o "$platform/launcher.exe" \
        "$root/main.c"
    "$zig" cc -target "$target" "${c_flags[@]}" -Os -s \
        -o "$platform/dispatcher.exe" \
        "$root/dispatcher.c"
}

compile_unix osx-aarch_64 aarch64-macos-none ''
compile_unix osx-x86_64 x86_64-macos-none ''
compile_unix linux-aarch_64 aarch64-linux-gnu -ldl
compile_unix linux-x86_64 x86_64-linux-gnu -ldl
compile_windows windows-aarch_64 aarch64-windows-gnu
compile_windows windows-x86_64 x86_64-windows-gnu

rm -rf "$output"
mkdir -p "$output"
for classifier in \
    osx-aarch_64 osx-x86_64 \
    linux-aarch_64 linux-x86_64 \
    windows-aarch_64 windows-x86_64; do
    mkdir -p "$output/$classifier"
    if [[ "$classifier" == windows-* ]]; then
        cp "$temporary/$classifier/launcher.exe" "$output/$classifier/"
        cp "$temporary/$classifier/dispatcher.exe" "$output/$classifier/"
    else
        cp "$temporary/$classifier/launcher" "$output/$classifier/"
        cp "$temporary/$classifier/dispatcher" "$output/$classifier/"
    fi
done
