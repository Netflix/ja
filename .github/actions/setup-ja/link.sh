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

if (($# != 3)); then
    echo "Usage: $0 <source-java-home> <output-java-home> <ja-version>" >&2
    exit 2
fi
source_java_home="$1"
output_java_home="$2"
ja_version="$3"
bootstrap_jig_version=0.16.2

if [[ -e "$output_java_home" ]]; then
    echo "Output path already exists: $output_java_home" >&2
    exit 1
fi
java="$source_java_home/bin/java"
jlink="$source_java_home/bin/jlink"
if [[ ! -x "$java" || ! -x "$jlink" || ! -f "$source_java_home/release" \
        || ! -f "$source_java_home/jmods/java.base.jmod" \
        || ! -f "$source_java_home/lib/src.zip" ]]; then
    echo "The source Java installation must provide java, jlink, a release file, JMODs, and lib/src.zip" >&2
    exit 1
fi

work="$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/ja-toolchain.XXXXXX")"
trap 'rm -rf "$work"' EXIT
mkdir -p "$(dirname "$output_java_home")"

bootstrap_jig="$work/com.netflix.tools.jig-$bootstrap_jig_version.jar"
curl --fail --silent --show-error --location \
    "https://repo.maven.apache.org/maven2/com/netflix/com.netflix.tools.jig/$bootstrap_jig_version/com.netflix.tools.jig-$bootstrap_jig_version.jar" \
    --output "$bootstrap_jig"

module_arguments="$work/modules.args"
"$java" \
    --module-path "$bootstrap_jig" \
    --module com.netflix.tools.jig \
    --module-path "$source_java_home/jmods" \
    --add-requires "com.netflix.tools.ja@$ja_version" \
    --add-modules com.netflix.tools.jfmt,com.netflix.tools.jist,com.netflix.tools.jdocserver \
    --prefer-jmod \
    --target-platform CURRENT \
    --resolve-options module-path \
    --write-argfile "$module_arguments"

resolved=()
while IFS= read -r line; do
    resolved+=("$line")
done < "$module_arguments"
if ((${#resolved[@]} != 2)) || [[ "${resolved[0]}" != --module-path ]]; then
    echo "jig did not produce one module path" >&2
    exit 1
fi

"$jlink" \
    @"$module_arguments" \
    --add-modules ALL-MODULE-PATH \
    --generate-cds-archive \
    --output "$output_java_home"

# Retain packaged modules so subsequent links can replace bundled modules.
mkdir -p "$output_java_home/jmods"
cp -p "$source_java_home/jmods/"*.jmod "$output_java_home/jmods/"
IFS=: read -r -a jmods <<< "${resolved[1]}"
for jmod in "${jmods[@]}"; do
    if [[ "$jmod" != "$source_java_home/jmods" ]]; then
        cp -p "$jmod" "$output_java_home/jmods/"
    fi
done
cp -p "$source_java_home/lib/src.zip" "$output_java_home/lib/src.zip"

[[ -x "$output_java_home/bin/ja" && -x "$output_java_home/bin/jig" ]]
[[ -x "$output_java_home/bin/jlink" && -x "$output_java_home/bin/jmod" ]]
[[ -f "$output_java_home/jmods/java.base.jmod" ]]
[[ -f "$output_java_home/lib/src.zip" ]]
"$output_java_home/bin/java" --list-modules | grep -Fqx "com.netflix.tools.ja@$ja_version"
"$output_java_home/bin/java" --list-modules | grep -Eq '^com\.netflix\.tools\.jig@'
if ! find "$output_java_home/lib" -type f -name '*.jsa' -print -quit | grep -q .; then
    echo "The linked toolchain has no CDS archive" >&2
    exit 1
fi
