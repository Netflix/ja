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

if (($# != 6)); then
    echo "Usage: $0 <source-java-home> <bootstrap-java-home> <artifacts> <output-java-home> <ja-version> <jig-version>" >&2
    exit 2
fi
source_java_home="$1"
bootstrap_java_home="$2"
artifacts="$3"
output_java_home="$4"
ja_version="$5"
jig_version="$6"

if [[ -e "$output_java_home" ]]; then
    echo "Output path already exists: $output_java_home" >&2
    exit 1
fi
jlink="$source_java_home/bin/jlink"
if [[ ! -x "$jlink" || ! -d "$bootstrap_java_home/jmods" \
        || ! -d "$bootstrap_java_home/lib/ja/modules" \
        || ! -f "$source_java_home/lib/src.zip" ]]; then
    echo "The source and bootstrap Java installations do not provide the required development artifacts" >&2
    exit 1
fi

case "$(uname -s):$(uname -m)" in
    Darwin:arm64) platform=osx-aarch_64 ;;
    Darwin:x86_64) platform=osx-x86_64 ;;
    Linux:aarch64 | Linux:arm64) platform=linux-aarch_64 ;;
    Linux:x86_64) platform=linux-x86_64 ;;
    *) echo "Unsupported build platform: $(uname -s) $(uname -m)" >&2; exit 1 ;;
esac

cli_jmod="$artifacts/com.netflix.tools.cli.jmod"
launcher_jmod="$artifacts/com.netflix.tools.launcher.jmod"
ja_jmod="$artifacts/com.netflix.tools.ja-$platform.jmod"
for artifact in "$cli_jmod" "$launcher_jmod" "$ja_jmod" \
        "$artifacts/com.netflix.tools.cli.jar" \
        "$artifacts/com.netflix.tools.launcher.jar" \
        "$artifacts/com.netflix.tools.ja.jar"; do
    if [[ ! -f "$artifact" ]]; then
        echo "Missing assembled artifact: $artifact" >&2
        exit 1
    fi
done

work="$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/ja-assembled-jdk.XXXXXX")"
trap 'rm -rf "$work"' EXIT
jig_jar="$work/com.netflix.tools.jig-$jig_version.jar"
jig_jmod="$work/com.netflix.tools.jig-$jig_version-$platform.jmod"
curl --fail --silent --show-error --location \
    "https://repo.maven.apache.org/maven2/com/netflix/com.netflix.tools.jig/$jig_version/com.netflix.tools.jig-$jig_version.jar" \
    --output "$jig_jar"
curl --fail --silent --show-error --location \
    "https://repo.maven.apache.org/maven2/com/netflix/com.netflix.tools.jig/$jig_version/com.netflix.tools.jig-$jig_version-$platform.jmod" \
    --output "$jig_jmod"

cp -Rp "$bootstrap_java_home/jmods" "$work/jmods"
for module in com.netflix.tools.cli com.netflix.tools.launcher com.netflix.tools.ja com.netflix.tools.jig; do
    find "$work/jmods" -maxdepth 1 -type f \
        \( -name "$module.jar" -o -name "$module-*.jar" \
        -o -name "$module.jmod" -o -name "$module-*.jmod" \) -delete
done
cp -p "$cli_jmod" "$work/jmods/com.netflix.tools.cli.jmod"
cp -p "$launcher_jmod" "$work/jmods/com.netflix.tools.launcher.jmod"
cp -p "$ja_jmod" "$work/jmods/com.netflix.tools.ja.jmod"
cp -p "$jig_jmod" "$work/jmods/com.netflix.tools.jig.jmod"

"$jlink" \
    --module-path "$work/jmods" \
    --add-modules ALL-MODULE-PATH \
    --generate-cds-archive \
    --output "$output_java_home"

cp -Rp "$work/jmods" "$output_java_home/jmods"
cp -p "$source_java_home/lib/src.zip" "$output_java_home/lib/src.zip"
mkdir -p "$output_java_home/lib/ja/modules"
cp -p "$bootstrap_java_home/lib/ja/modules/"*.jar "$output_java_home/lib/ja/modules/"
for module in com.netflix.tools.cli com.netflix.tools.launcher com.netflix.tools.ja com.netflix.tools.jig; do
    find "$output_java_home/lib/ja/modules" -maxdepth 1 -type f \
        \( -name "$module.jar" -o -name "$module-*.jar" \) -delete
done
cp -p "$artifacts/com.netflix.tools.cli.jar" "$output_java_home/lib/ja/modules/"
cp -p "$artifacts/com.netflix.tools.launcher.jar" "$output_java_home/lib/ja/modules/"
cp -p "$artifacts/com.netflix.tools.ja.jar" "$output_java_home/lib/ja/modules/"
cp -p "$jig_jar" "$output_java_home/lib/ja/modules/"

[[ -x "$output_java_home/bin/ja" ]]
[[ -f "$output_java_home/jmods/java.base.jmod" ]]
[[ -f "$output_java_home/lib/src.zip" ]]
"$output_java_home/bin/java" --list-modules | grep -Fqx "com.netflix.tools.ja@$ja_version"
"$output_java_home/bin/java" --list-modules | grep -Fqx "com.netflix.tools.jig@$jig_version"
if ! find "$output_java_home/lib" -type f -name '*.jsa' -print -quit | grep -q .; then
    echo "The linked development JDK has no CDS archive" >&2
    exit 1
fi
