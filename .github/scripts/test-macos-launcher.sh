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

if [[ "$(uname -s)" != Darwin ]]; then
    echo "This test requires macOS" >&2
    exit 2
fi
if [[ -z "${JAVA_HOME:-}" || ! -x "$JAVA_HOME/bin/ja" || ! -x "$JAVA_HOME/bin/jfmt" ]]; then
    echo "JAVA_HOME must identify a ja-enabled JDK" >&2
    exit 2
fi
case "$(uname -m)" in
    arm64) architecture=aarch_64 ;;
    x86_64) architecture=x86_64 ;;
    *) echo "Unsupported macOS architecture" >&2; exit 2 ;;
esac
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
launcher="$root/src/com.netflix.tools.launcher/META-INF/com.netflix.tools.launcher/osx-$architecture/launcher"
if [[ ! -x "$launcher" ]]; then
    echo "Native launcher is missing; run src/launcher/build.sh first" >&2
    exit 1
fi

work="$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/ja-macos-launcher.XXXXXX")"
cleanup() {
    # jig makes cached module outputs read-only.
    chmod -R u+w "$work"
    rm -rf "$work"
}
trap cleanup EXIT

# Exercise the newly built native code with a real libjli and tool providers.
# Use a disposable image rather than modifying the cached bootstrap JDK.
source_java_home="$(cd "$JAVA_HOME" && pwd -P)"
cp -R "$source_java_home" "$work/jdk"
for tool in ja jfmt; do
    rm "$work/jdk/bin/$tool"
    cp "$launcher" "$work/jdk/bin/$tool"
    grep -Fxq -- '-L-aot=auto' "$work/jdk/conf/com.netflix.tools.launcher/$tool.args"
    export XDG_CACHE_HOME="$work/cache-$tool"
    mkdir -p "$XDG_CACHE_HOME"

    if ! "$work/jdk/bin/$tool" -L-aot=off --version > "$work/$tool.stdout" 2> "$work/$tool.stderr"; then
        cat "$work/$tool.stderr" >&2
        exit 1
    fi
    grep -Eq "^$tool [0-9]" "$work/$tool.stdout"
    if [[ -s "$work/$tool.stderr" || -n "$(find "$XDG_CACHE_HOME" -mindepth 1 -print -quit)" ]]; then
        echo "$tool -L-aot=off unexpectedly attempted AOT setup" >&2
        cat "$work/$tool.stderr" >&2
        exit 1
    fi

    if ! "$work/jdk/bin/$tool" -L-aot=create --version > "$work/$tool.stdout" 2> "$work/$tool.stderr"; then
        cat "$work/$tool.stderr" >&2
        exit 1
    fi
    if [[ -z "$(find "$XDG_CACHE_HOME" -type f -name aot -print -quit)" ]]; then
        echo "$tool did not create an AOT cache" >&2
        cat "$work/$tool.stderr" >&2
        exit 1
    fi

    # 'on' makes the VM require a usable cache, rather than silently falling back.
    if ! "$work/jdk/bin/$tool" -L-aot=auto -J-XX:AOTMode=on --version > "$work/$tool.stdout" 2> "$work/$tool.stderr"; then
        cat "$work/$tool.stderr" >&2
        exit 1
    fi
    if grep -Fq 'AOT training run' "$work/$tool.stderr"; then
        echo "$tool repeated AOT training while loading an existing cache" >&2
        cat "$work/$tool.stderr" >&2
        exit 1
    fi
    printf '%s: AOT off, creation, and required cache loading passed\n' "$tool"
done
