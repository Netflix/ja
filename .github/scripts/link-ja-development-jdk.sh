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
bootstrap_jig_version=0.13.4

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

work="$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/ja-development-jdk.XXXXXX")"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work"

bootstrap_jig="$work/com.netflix.tools.jig-$bootstrap_jig_version.jar"
curl --fail --silent --show-error --location \
    "https://repo.maven.apache.org/maven2/com/netflix/com.netflix.tools.jig/$bootstrap_jig_version/com.netflix.tools.jig-$bootstrap_jig_version.jar" \
    --output "$bootstrap_jig"

resolve=(
    --add-requires "com.netflix.tools.ja@$ja_version"
    --target-platform CURRENT
    --compile-time
    --resolve-options "module-path,add-modules,module=main"
)
"$java" \
    --module-path "$bootstrap_jig" \
    --module com.netflix.tools.jig/com.netflix.tools.jig.Jig \
    "${resolve[@]}" \
    --prefer-jmod \
    --write-argfile "$work/jmods.args"
"$java" \
    --module-path "$bootstrap_jig" \
    --module com.netflix.tools.jig/com.netflix.tools.jig.Jig \
    "${resolve[@]}" \
    --write-argfile "$work/jars.args"

jmod_arguments=()
while IFS= read -r line; do
    jmod_arguments+=("$line")
done < "$work/jmods.args"
jar_arguments=()
while IFS= read -r line; do
    jar_arguments+=("$line")
done < "$work/jars.args"
if ((${#jmod_arguments[@]} < 2)) || [[ "${jmod_arguments[0]}" != --module-path \
        || ${#jar_arguments[@]} -lt 2 || "${jar_arguments[0]}" != --module-path ]]; then
    echo "jig did not produce the expected module paths" >&2
    exit 1
fi
jmod_path="${jmod_arguments[1]}"
jar_path="${jar_arguments[1]}"

"$jlink" \
    --module-path "$source_java_home/jmods:$jmod_path" \
    --add-modules ALL-MODULE-PATH \
    --generate-cds-archive \
    --output "$output_java_home"

# Keep the linkable JDK and source archive, and retain resolved artifacts for subsequent assembly and linking.
cp -Rp "$source_java_home/jmods" "$output_java_home/jmods"
IFS=: read -r -a jmods <<< "$jmod_path"
cp -p "${jmods[@]}" "$output_java_home/jmods/"
cp -p "$source_java_home/lib/src.zip" "$output_java_home/lib/src.zip"
mkdir -p "$output_java_home/lib/ja/modules"
IFS=: read -r -a jars <<< "$jar_path"
cp -p "${jars[@]}" "$output_java_home/lib/ja/modules/"

add_modules=""
for ((index = 0; index < ${#jar_arguments[@]}; index++)); do
    if [[ "${jar_arguments[index]}" == --add-modules ]]; then
        add_modules="${jar_arguments[index + 1]}"
    fi
done
if [[ -z "$add_modules" ]]; then
    echo "jig did not produce application roots" >&2
    exit 1
fi

# Bootstrap linking does not project runtime-access attributes from binary application modules.
# Keep these in sync with the declarations on ja's static development-tool dependencies.
cat > "$output_java_home/conf/com.netflix.tools.launcher/ja.args" <<EOF
--enable-native-access=com.netflix.tools.ja
--add-exports=jdk.javadoc/jdk.javadoc.internal.doclets.formats.html=com.netflix.tools.jdocserver
--add-exports=jdk.javadoc/jdk.javadoc.internal.api=com.netflix.tools.jdocserver
--add-exports=jdk.javadoc/jdk.javadoc.internal.doclets.toolkit.util=com.netflix.tools.jdocserver
--add-exports=jdk.javadoc/jdk.javadoc.internal.tool=com.netflix.tools.jdocserver
--add-exports=jdk.compiler/com.sun.tools.javac.main=com.netflix.tools.jdocserver
--add-exports=jdk.compiler/com.sun.tools.javac.util=com.netflix.tools.jdocserver
--add-exports=jdk.compiler/com.sun.tools.javac.api=com.netflix.tools.jfmt
--add-exports=jdk.compiler/com.sun.tools.javac.code=com.netflix.tools.jfmt
--add-exports=jdk.compiler/com.sun.tools.javac.file=com.netflix.tools.jfmt
--add-exports=jdk.compiler/com.sun.tools.javac.model=com.netflix.tools.jfmt
--add-exports=jdk.compiler/com.sun.tools.javac.parser=com.netflix.tools.jfmt
--add-exports=jdk.compiler/com.sun.tools.javac.tree=com.netflix.tools.jfmt
--add-exports=jdk.compiler/com.sun.tools.javac.util=com.netflix.tools.jfmt
--add-modules=$add_modules
-L-aot=auto
EOF

[[ -x "$output_java_home/bin/ja" ]]
[[ -f "$output_java_home/jmods/java.base.jmod" ]]
[[ -f "$output_java_home/lib/src.zip" ]]
"$output_java_home/bin/java" --list-modules | grep -Fqx "com.netflix.tools.ja@$ja_version"
if ! find "$output_java_home/lib" -type f -name '*.jsa' -print -quit | grep -q .; then
    echo "The linked development JDK has no CDS archive" >&2
    exit 1
fi
