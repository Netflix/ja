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
    echo "Usage: $0 [output-directory]" >&2
    exit 2
fi
requested_ja_home="${1:-}"
configured_java_home="${JAVA_HOME:-}"
java_on_path="$(type -P java || true)"
java_properties=""

jig_version="${JIG_VERSION:-0.16.3}"
ja_version="${JA_VERSION:-}"
if [[ -n "$configured_java_home" ]]; then
    source_java_home="$configured_java_home"
    java="$source_java_home/bin/java"
else
    java="$java_on_path"
    if [[ -z "$java" ]]; then
        echo "A JDK 25 or later installation must be available through JAVA_HOME or PATH" >&2
        exit 1
    fi
    if ! java_properties="$("$java" -XshowSettings:properties -version 2>&1)"; then
        echo "Unable to inspect the Java installation on PATH" >&2
        exit 1
    fi
    source_java_home="$(awk -F ' = ' '/^[[:space:]]*java.home = / { print $2; exit }' <<< "$java_properties")"
fi
if [[ -z "$java_properties" ]]; then
    if ! java_properties="$("$java" -XshowSettings:properties -version 2>&1)"; then
        echo "Unable to inspect the Java installation" >&2
        exit 1
    fi
fi
java_vm_name="$(awk -F ' = ' '/^[[:space:]]*java.vm.name = / { print $2; exit }' <<< "$java_properties")"
case "$java_vm_name" in
    *OpenJ9*) openj9=true ;;
    *) openj9=false ;;
esac

release="$source_java_home/release"
jlink="$source_java_home/bin/jlink"
if [[ ! -x "$java" || ! -x "$jlink" || ! -f "$release" || ! -f "$source_java_home/lib/src.zip" ]]; then
    echo "Java must be a JDK 25 or later installation with jlink, a release file, and lib/src.zip" >&2
    exit 1
fi
jdk_module_path=""
if [[ -d "$source_java_home/jmods" ]]; then
    jdk_module_path="$source_java_home/jmods"
elif ! LC_ALL=C "$jlink" --help 2>&1 | grep -Fq "Linking from run-time image enabled"; then
    echo "Java must provide JMODs or be built with --enable-linkable-runtime" >&2
    exit 1
fi
java_version="$(awk -F= '$1 == "JAVA_VERSION" { gsub(/^"|"$/, "", $2); print $2; exit }' "$release")"
if [[ ! "$java_version" =~ ^([0-9]+)([.+-].*)?$ ]] || ((BASH_REMATCH[1] < 25)); then
    echo "Java 25 or later is required, found ${java_version:-an unknown version}" >&2
    exit 1
fi
java_feature="${BASH_REMATCH[1]}"

mac_bundle=""
if [[ -n "$requested_ja_home" ]]; then
    ja_home="$requested_ja_home"
else
    case "$(uname -s)" in
        Darwin)
            mac_bundle="$HOME/Library/Java/JavaVirtualMachines/ja-$java_feature.jdk"
            ja_home="$mac_bundle/Contents/Home"
            ;;
        Linux)
            ja_home="$HOME/.jdks/ja-$java_feature"
            ;;
        *)
            echo "An output directory is required on this operating system" >&2
            exit 2
            ;;
    esac
fi
install_root="${mac_bundle:-$ja_home}"
if [[ -e "$install_root" || -L "$install_root" ]]; then
    echo "Output path already exists: $install_root" >&2
    exit 1
fi
if [[ -n "$mac_bundle" ]]; then
    source_java_home_physical="$(cd "$source_java_home" && pwd -P)"
    source_contents="$(dirname "$source_java_home_physical")"
    if [[ "$(basename "$source_java_home_physical")" != Home
            || "$(basename "$source_contents")" != Contents
            || ! -f "$source_contents/Info.plist"
            || ! -d "$source_contents/MacOS" ]]; then
        echo "The default macOS installation requires a native JDK bundle" >&2
        echo "Pass an output directory to install from this JDK" >&2
        exit 1
    fi
fi

java_selection=path
jenv_root="${JENV_ROOT:-$HOME/.jenv}"
sdkman_candidates="${SDKMAN_CANDIDATES_DIR:-${SDKMAN_DIR:-$HOME/.sdkman}/candidates}"
if [[ "$java_on_path" == "$jenv_root/shims/java"
        || "$configured_java_home" == "$jenv_root/versions/"* ]]; then
    java_selection=jenv
elif [[ "$configured_java_home" == "$sdkman_candidates/java/"* ]]; then
    java_selection=sdkman
elif [[ -n "$configured_java_home" ]]; then
    java_selection=java_home
fi

if [[ -n "${JA_BIN_HOME:-}" ]]; then
    ja_bin_home="$JA_BIN_HOME"
elif [[ -n "${XDG_BIN_HOME:-}" ]]; then
    ja_bin_home="$XDG_BIN_HOME"
elif [[ -n "${XDG_DATA_HOME:-}" ]]; then
    ja_bin_home="$(dirname "$XDG_DATA_HOME")/bin"
else
    ja_bin_home="$HOME/.local/bin"
fi
case ":${PATH:-}:" in
    *":$ja_bin_home:"*) ja_bin_on_path=true ;;
    *) ja_bin_on_path=false ;;
esac

work="$(mktemp -d "${TMPDIR:-/tmp}/ja-install.XXXXXX")"
stage_parent=""
cleanup() {
    rm -rf "$work"
    if [[ -n "$stage_parent" ]]; then
        rm -rf "$stage_parent"
    fi
}
trap cleanup EXIT

jig_home="$work/home"
mkdir -p "$jig_home"

if [[ -z "$ja_version" ]]; then
    printf 'Finding the latest ja release...\n'
fi
jig="$work/com.netflix.tools.jig-$jig_version.jar"
curl --fail --silent --show-error --location --output "$jig" \
    "https://repo.maven.apache.org/maven2/com/netflix/com.netflix.tools.jig/$jig_version/com.netflix.tools.jig-$jig_version.jar"

jig_command=(
    "$java"
    -Duser.home="$jig_home"
    --upgrade-module-path "$jig"
    --module com.netflix.tools.jig/com.netflix.tools.jig.Jig
)
if [[ -z "$ja_version" ]]; then
    ja_version="$("${jig_command[@]}" \
        --list-module-versions com.netflix.tools.ja | tail -n 1)"
    if [[ -z "$ja_version" ]]; then
        echo "Unable to determine the latest ja version" >&2
        exit 1
    fi
fi
printf 'Installing ja %s with JDK %s...\n' "$ja_version" "$java_version"

resolved_arguments="$work/resolved.args"
"${jig_command[@]}" \
    --add-requires "com.netflix.tools.ja@$ja_version" \
    --add-modules com.netflix.tools.jfmt,com.netflix.tools.jist,com.netflix.tools.jdocserver \
    --prefer-jmod \
    --target-platform CURRENT \
    --resolve-options module-path,upgrade-module-path \
    > "$resolved_arguments"

module_path=""
upgrade_module_path=""
while IFS= read -r option && IFS= read -r value; do
    case "$option" in
        --module-path) module_path="$value" ;;
        --upgrade-module-path) upgrade_module_path="$value" ;;
        *) echo "Unexpected Jig argument: $option" >&2; exit 1 ;;
    esac
done < "$resolved_arguments"
resolved_module_path="$module_path"
if [[ -n "$upgrade_module_path" ]]; then
    resolved_module_path="$upgrade_module_path${resolved_module_path:+:$resolved_module_path}"
fi
if [[ -n "$jdk_module_path" ]]; then
    module_path="$jdk_module_path${module_path:+:$module_path}"
fi
if [[ -n "$upgrade_module_path" ]]; then
    module_path="$upgrade_module_path${module_path:+:$module_path}"
fi
if [[ -z "$module_path" ]]; then
    echo "Jig did not provide the module path required by jlink" >&2
    exit 1
fi

link_options=()
if [[ "$openj9" == false ]]; then
    link_options+=(--generate-cds-archive)
fi
install_parent="$(dirname "$install_root")"
mkdir -p "$install_parent"
stage_parent="$(mktemp -d "$install_parent/.ja-install.XXXXXX")"
staged_install_root="$stage_parent/ja"
if [[ -n "$mac_bundle" ]]; then
    staged_ja_home="$staged_install_root/Contents/Home"
else
    staged_ja_home="$staged_install_root"
fi

jlink_output="$work/jlink-output"
if ! "$jlink" --module-path "$module_path" --add-modules ALL-MODULE-PATH \
        "${link_options[@]}" --output "$staged_ja_home" > "$jlink_output" 2>&1; then
    cat "$jlink_output" >&2
    echo "Unable to create the ja-enabled JDK" >&2
    exit 1
fi
cp "$source_java_home/lib/src.zip" "$staged_ja_home/lib/src.zip"

if [[ -d "$source_java_home/jmods" ]]; then
    mkdir -p "$staged_ja_home/jmods"
    cp "$source_java_home/jmods/"*.jmod "$staged_ja_home/jmods/"
    IFS=: read -r -a resolved_modules <<< "$resolved_module_path"
    for resolved_module in "${resolved_modules[@]}"; do
        [[ "$resolved_module" == *.jmod && -f "$resolved_module" ]] || continue
        module_name="${resolved_module##*/}"
        module_name="${module_name%%-*}"
        cp "$resolved_module" "$staged_ja_home/jmods/$module_name.jmod"
    done
fi
mkdir -p "$staged_ja_home/lib/ja/modules"
find "$jig_home" -type f -name '*.jar' -exec cp {} "$staged_ja_home/lib/ja/modules/" \;
if [[ "$openj9" == true ]]; then
    for options in "$staged_ja_home/conf/com.netflix.tools.launcher/"*.args; do
        [[ -f "$options" ]] || continue
        grep -Fxq -- -L-aot=auto "$options" || continue
        command="${options##*/}"
        command="${command%.args}"
        warmup_output="$work/$command-warmup-output"
        if ! "$staged_ja_home/bin/$command" -L-aot=create --help \
                > "$warmup_output" 2>&1; then
            cat "$warmup_output" >&2
            echo "Unable to warm the $command shared class cache" >&2
            exit 1
        fi
    done
fi

if [[ -n "$mac_bundle" ]]; then
    contents="$staged_install_root/Contents"
    cp "$source_contents/Info.plist" "$contents/Info.plist"
    cp -R "$source_contents/MacOS" "$contents/MacOS"
fi

installed_module="$("$staged_ja_home/bin/java" --describe-module com.netflix.tools.ja)"
installed_module="${installed_module%%$'\n'*}"
if [[ "$installed_module" != "com.netflix.tools.ja@$ja_version" ]]; then
    echo "Unable to verify ja $ja_version, found $installed_module" >&2
    exit 1
fi
if [[ -e "$install_root" || -L "$install_root" ]]; then
    echo "Output path was created during installation: $install_root" >&2
    exit 1
fi
mv "$staged_install_root" "$install_root"

printf 'ja %s installed in %s\n' "$ja_version" "$ja_home"
ja_alias="ja-$java_feature"
case "$java_selection" in
    jenv)
        printf '\nThe source JDK is selected by jenv. Register and select ja with:\n\n'
        printf '  jenv add %q %q\n' "$ja_alias" "$ja_home"
        printf '  jenv shell %q\n' "$ja_alias"
        printf '\nUse jenv local or jenv global instead to select ja more broadly.\n'
        ;;
    sdkman)
        printf '\nThe source JDK is selected by SDKMAN!. Register and select ja with:\n\n'
        printf '  sdk install java %q %q\n' "$ja_alias" "$ja_home"
        printf '  sdk use java %q\n' "$ja_alias"
        printf '\nUse sdk default instead to select ja in future shells.\n'
        ;;
    java_home)
        activation_path="$ja_home/bin"
        if [[ "$ja_bin_on_path" == false ]]; then
            activation_path="$activation_path:$ja_bin_home"
        fi
        printf '\nTo use ja in this shell:\n\n'
        printf '  export JAVA_HOME=%q\n' "$ja_home"
        printf "  export PATH=%q:\"\$PATH\"\n" "$activation_path"
        ;;
    path)
        activation_path="$ja_home/bin"
        if [[ "$ja_bin_on_path" == false ]]; then
            activation_path="$activation_path:$ja_bin_home"
        fi
        printf '\nTo use ja in this shell:\n\n'
        printf "  export PATH=%q:\"\$PATH\"\n" "$activation_path"
        ;;
esac
if [[ "$java_selection" == jenv || "$java_selection" == sdkman ]] && [[ "$ja_bin_on_path" == false ]]; then
    printf '\nCommands installed by ja use %s. To use them in this shell:\n\n' "$ja_bin_home"
    printf "  export PATH=%q:\"\$PATH\"\n" "$ja_bin_home"
fi

case "${SHELL##*/}" in
    fish)
        completion_command='ja completion fish | source'
        completion_shell=Fish
        ;;
    zsh)
        completion_command="eval \"\$(ja completion zsh)\""
        completion_shell=Zsh
        ;;
    *)
        completion_command="eval \"\$(ja completion bash)\""
        completion_shell=Bash
        ;;
esac
printf '\nAfter activating ja, enable completions in this %s session with:\n\n' "$completion_shell"
printf '  %s\n' "$completion_command"
printf '\nAdd this command to your shell configuration to enable completions in future sessions.\n'
