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

if (($# > 1)); then
    echo "Usage: $0 [ja-version]" >&2
    exit 2
fi
ja_version="${1:-}"

if [[ -z "${JAVA_HOME:-}" || ! -f "$JAVA_HOME/release" ]]; then
    echo "JAVA_HOME must identify the source JDK" >&2
    exit 1
fi
java_version="$(awk -F= '$1 == "JAVA_VERSION" { gsub(/^"|"$/, "", $2); print $2; exit }' "$JAVA_HOME/release")"
if [[ ! "$java_version" =~ ^([0-9]+) ]]; then
    echo "Unable to determine the Java feature version from $java_version" >&2
    exit 1
fi
java_feature="${BASH_REMATCH[1]}"
expected_java_vm="${EXPECTED_JAVA_VM:-HotSpot}"
expected_link_mode="${EXPECTED_LINK_MODE:-jmods}"
java_properties="$("$JAVA_HOME/bin/java" -XshowSettings:properties -version 2>&1)"
java_vm_name="$(awk -F ' = ' '/^[[:space:]]*java.vm.name = / { print $2; exit }' <<< "$java_properties")"
case "$expected_java_vm" in
    HotSpot)
        if [[ "$java_vm_name" == *OpenJ9* ]]; then
            echo "Expected a HotSpot source JDK, found $java_vm_name" >&2
            exit 1
        fi
        ;;
    OpenJ9)
        if [[ "$java_vm_name" != *OpenJ9* ]]; then
            echo "Expected an OpenJ9 source JDK, found $java_vm_name" >&2
            exit 1
        fi
        ;;
    *)
        echo "Unsupported expected Java VM: $expected_java_vm" >&2
        exit 2
        ;;
esac
case "$expected_link_mode" in
    jmods)
        [[ -f "$JAVA_HOME/jmods/java.base.jmod" ]]
        ;;
    linkable)
        [[ ! -d "$JAVA_HOME/jmods" ]]
        jlink_help="$("$JAVA_HOME/bin/jlink" --help 2>&1)"
        grep -Fq "Linking from run-time image enabled" <<< "$jlink_help"
        ;;
    *)
        echo "Unsupported expected link mode: $expected_link_mode" >&2
        exit 2
        ;;
esac

case "$(uname -s)" in
    Darwin)
        install_root="$HOME/Library/Java/JavaVirtualMachines/ja-$java_feature.jdk"
        ja_home="$install_root/Contents/Home"
        ;;
    Linux)
        install_root="$HOME/.jdks/ja-$java_feature"
        ja_home="$install_root"
        ;;
    *)
        echo "Unsupported test operating system" >&2
        exit 2
        ;;
esac
if [[ -e "$install_root" || -L "$install_root" ]]; then
    echo "Test installation path already exists: $install_root" >&2
    exit 1
fi

work="$(mktemp -d "${TMPDIR:-/tmp}/ja-install-test.XXXXXX")"
cleanup() {
    rm -rf "$install_root" "$work"
}
trap cleanup EXIT

if ! JA_VERSION="$ja_version" SHELL=/bin/bash ./install.sh > "$work/stdout" 2> "$work/stderr"; then
    cat "$work/stdout"
    cat "$work/stderr" >&2
    exit 1
fi
if [[ -s "$work/stderr" ]]; then
    echo "The installer wrote to standard error:" >&2
    cat "$work/stderr" >&2
    exit 1
fi

[[ -x "$ja_home/bin/ja" ]]
installed_module="$("$ja_home/bin/java" --describe-module com.netflix.tools.ja)"
installed_module="${installed_module%%$'\n'*}"
installed_version="${installed_module#com.netflix.tools.ja@}"
if [[ -n "$ja_version" && "$installed_version" != "$ja_version" ]]; then
    echo "Expected ja $ja_version, found $installed_module" >&2
    exit 1
fi
mkdir -p "$work/home"
if ! HOME="$work/home" "$ja_home/bin/ja" --version \
        > "$work/ja-stdout" 2> "$work/ja-stderr"; then
    cat "$work/ja-stdout"
    cat "$work/ja-stderr" >&2
    exit 1
fi
grep -Fqx "ja $installed_version" "$work/ja-stdout"

grep -Fqx "Installing ja $installed_version with JDK $java_version..." "$work/stdout"
grep -Fqx "ja $installed_version installed in $ja_home" "$work/stdout"
if grep -Eq '% Total|Using incubator modules|Created CDS archive|replacing existing signature' "$work/stdout"; then
    echo "The installer exposed implementation output:" >&2
    cat "$work/stdout" >&2
    exit 1
fi

[[ -f "$ja_home/lib/src.zip" ]]
installed_modules="$("$ja_home/bin/java" --list-modules)"
grep -Fqx "com.netflix.tools.ja@$installed_version" <<< "$installed_modules"
installed_properties="$("$ja_home/bin/java" -XshowSettings:properties -version 2>&1)"
installed_vm_name="$(awk -F ' = ' '/^[[:space:]]*java.vm.name = / { print $2; exit }' <<< "$installed_properties")"
if [[ "$expected_java_vm" == OpenJ9 ]]; then
    [[ "$installed_vm_name" == *OpenJ9* ]]
    [[ -n "$(find "$ja_home/lib/ja/sharedclasses" -type f -print -quit)" ]]
else
    [[ "$installed_vm_name" != *OpenJ9* ]]
    [[ -n "$(find "$ja_home/lib" -type f -name '*.jsa' -print -quit)" ]]
fi
if [[ "$expected_link_mode" == jmods ]]; then
    [[ -f "$ja_home/jmods/java.base.jmod" ]]
else
    [[ ! -d "$ja_home/jmods" ]]
fi

if [[ "$(uname -s)" == Darwin ]]; then
    discovery_deadline=$((SECONDS + 60))
    while true; do
        discovered_jdks="$(/usr/libexec/java_home -V 2>&1)"
        if grep -Fq "$ja_home" <<< "$discovered_jdks"; then
            break
        fi
        if ((SECONDS >= discovery_deadline)); then
            echo "The ja-enabled JDK was not discovered: $ja_home" >&2
            exit 1
        fi
        sleep 1
    done
    source_java_home="$(cd "$JAVA_HOME" && pwd -P)"
    source_contents="$(dirname "$source_java_home")"
    cmp "$source_contents/Info.plist" "$install_root/Contents/Info.plist"
    diff -qr "$source_contents/MacOS" "$install_root/Contents/MacOS"
    [[ ! -e "$install_root/Contents/_CodeSignature" ]]
fi

if [[ -n "$(find "$(dirname "$install_root")" -maxdepth 1 -name '.ja-install.*' -print -quit)" ]]; then
    echo "The installer left a staging directory behind" >&2
    exit 1
fi
