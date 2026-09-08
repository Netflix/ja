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

if (($# != 4)); then
    echo "Usage: $0 <source-java-home> <artifacts> <module> <output-java-home>" >&2
    exit 2
fi
source_java_home="$1"
artifacts="$2"
module="$3"
output_java_home="$4"

if [[ -e "$output_java_home" ]]; then
    echo "Output path already exists: $output_java_home" >&2
    exit 1
fi
jlink="$source_java_home/bin/jlink"
jmod="$source_java_home/bin/jmod"
if [[ ! -x "$jlink" || ! -x "$jmod" || ! -d "$source_java_home/jmods" \
        || ! -f "$source_java_home/lib/src.zip" ]]; then
    echo "The Ja toolchain does not provide jlink, jmod, retained JMODs, and lib/src.zip" >&2
    exit 1
fi

case "$(uname -s):$(uname -m)" in
    Darwin:arm64) classifier=osx-aarch_64 ;;
    Darwin:x86_64) classifier=osx-x86_64 ;;
    Linux:aarch64 | Linux:arm64) classifier=linux-aarch_64 ;;
    Linux:x86_64) classifier=linux-x86_64 ;;
    *) echo "Unsupported build platform: $(uname -s) $(uname -m)" >&2; exit 1 ;;
esac

assembled_jmod="$artifacts/$module-$classifier.jmod"
if [[ ! -f "$assembled_jmod" ]]; then
    echo "Missing assembled JMOD: $assembled_jmod" >&2
    exit 1
fi
descriptor="$("$jmod" describe "$assembled_jmod" | sed -n '1p')"
if [[ "$descriptor" != "$module"@* ]]; then
    echo "Assembled JMOD does not contain a versioned $module module: $descriptor" >&2
    exit 1
fi

work="$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/ja-link-tool.XXXXXX")"
trap 'chmod -R u+w "$work" 2>/dev/null || true; rm -rf "$work"' EXIT
mkdir -p "$work/jmods"
cp -p "$assembled_jmod" "$work/jmods/$module.jmod"

"$jlink" \
    --module-path "$work/jmods:$source_java_home/jmods" \
    --add-modules ALL-MODULE-PATH \
    --generate-cds-archive \
    --output "$output_java_home"

# Keep this as a development JDK so the built toolchain can compile, assemble, and link projects.
cp -Rp "$source_java_home/jmods" "$output_java_home/jmods"
for candidate in "$output_java_home/jmods/"*.jmod; do
    described_module="$("$jmod" describe "$candidate" | sed -n '1{s/@.*//;p;}')"
    if [[ "$described_module" == "$module" ]]; then
        rm "$candidate"
    fi
done
cp -p "$assembled_jmod" "$output_java_home/jmods/$module.jmod"
cp -p "$source_java_home/lib/src.zip" "$output_java_home/lib/src.zip"

[[ -f "$output_java_home/jmods/java.base.jmod" ]]
[[ -f "$output_java_home/lib/src.zip" ]]
"$output_java_home/bin/java" --list-modules | grep -Fqx "$descriptor"
if ! find "$output_java_home/lib" -type f -name '*.jsa' -print -quit | grep -q .; then
    echo "The linked development JDK has no CDS archive" >&2
    exit 1
fi
