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

if (($# != 2)); then
    echo "Usage: $0 <archive> <ja-version>" >&2
    exit 2
fi
archive="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
ja_version="$2"

if [[ -z "${JAVA_HOME:-}" || ! -x "$JAVA_HOME/bin/java" ]]; then
    echo "JAVA_HOME must identify the source JDK" >&2
    exit 1
fi
if [[ ! -f "$archive" ]]; then
    echo "Standalone archive does not exist: $archive" >&2
    exit 1
fi

work="$(mktemp -d "${TMPDIR:-/tmp}/ja-standalone-install-test.XXXXXX")"
cleanup() {
    chmod -R u+w "$work" 2>/dev/null || true
    rm -rf "$work"
}
trap cleanup EXIT
applications="$work/applications"
commands="$work/bin"
ja_home="$applications/com.netflix.tools.ja@$ja_version"

if ! HOME="$work/home" \
        JA_INSTALL_HOME="$applications" \
        JA_BIN_HOME="$commands" \
        JA_STANDALONE_ARCHIVE="$archive" \
        JA_VERSION="$ja_version" \
        SHELL=/bin/bash \
        ./install.sh --standalone > "$work/stdout" 2> "$work/stderr"; then
    cat "$work/stdout"
    cat "$work/stderr" >&2
    exit 1
fi
if [[ -s "$work/stderr" ]]; then
    echo "The standalone installer wrote to standard error:" >&2
    cat "$work/stderr" >&2
    exit 1
fi

grep -Fqx "Ja $ja_version standalone distribution installed in $ja_home" "$work/stdout"
[[ -x "$ja_home/bin/ja" ]]
[[ -x "$ja_home/lib/com.netflix.tools.launcher/dispatcher" ]]
[[ -f "$ja_home/app/modules.hash" ]]
[[ ! -e "$ja_home/bin/java" ]]
[[ -x "$commands/ja" ]]
grep -Fqx "$ja_home/bin/ja" "$commands/ja.current"

HOME="$work/home" "$commands/ja" --version > "$work/java-home-version"
grep -Fqx "ja $ja_version" "$work/java-home-version"
env -u JAVA_HOME HOME="$work/home" PATH="$commands:$JAVA_HOME/bin" \
    "$commands/ja" --version > "$work/path-version"
grep -Fqx "ja $ja_version" "$work/path-version"

if find "$applications" -maxdepth 1 -name '.install-*' -print -quit | grep -q .; then
    echo "The standalone installer left a staging directory behind" >&2
    exit 1
fi
