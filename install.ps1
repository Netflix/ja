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

param(
    [string] $JigVersion = "0.13.2",
    [string] $JaVersion,
    [string] $Output
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$ConfiguredJavaHome = $env:JAVA_HOME
$JavaProperties = $null
$SourceFromJavaHome = -not [string]::IsNullOrWhiteSpace($ConfiguredJavaHome)
if ($SourceFromJavaHome) {
    $SourceJavaHome = $ConfiguredJavaHome
    $Java = Join-Path $SourceJavaHome "bin\java.exe"
} else {
    $JavaCommand = Get-Command java -CommandType Application -ErrorAction SilentlyContinue
    if ($null -eq $JavaCommand) {
        throw "A JDK 25 or later installation must be available through JAVA_HOME or PATH"
    }
    $Java = $JavaCommand.Source
    $JavaProperties = & $Java -XshowSettings:properties -version 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to inspect the Java installation on PATH"
    }
    $SourceJavaHome = $null
    foreach ($Line in $JavaProperties) {
        if ([string] $Line -match "^\s*java\.home = (.+)\s*$") {
            $SourceJavaHome = $Matches[1].Trim()
            break
        }
    }
    if ([string]::IsNullOrWhiteSpace($SourceJavaHome)) {
        throw "Unable to locate the Java installation on PATH"
    }
}
if ($null -eq $JavaProperties) {
    $JavaProperties = & $Java -XshowSettings:properties -version 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to inspect the Java installation"
    }
}
$JavaVmName = $null
foreach ($Line in $JavaProperties) {
    if ([string] $Line -match "^\s*java\.vm\.name = (.+)\s*$") {
        $JavaVmName = $Matches[1].Trim()
        break
    }
}
$OpenJ9 = -not [string]::IsNullOrWhiteSpace($JavaVmName) -and
    $JavaVmName -match "(?i)OpenJ9"

$Release = Join-Path $SourceJavaHome "release"
$Jlink = Join-Path $SourceJavaHome "bin\jlink.exe"
$Sources = Join-Path $SourceJavaHome "lib\src.zip"
if (-not (Test-Path -PathType Leaf $Java) -or
        -not (Test-Path -PathType Leaf $Jlink) -or
        -not (Test-Path -PathType Leaf $Release) -or
        -not (Test-Path -PathType Leaf $Sources)) {
    throw "Java must be a JDK 25 or later installation with jlink, a release file, and lib/src.zip"
}
$JdkModulePath = Join-Path $SourceJavaHome "jmods"
if (-not (Test-Path -PathType Container $JdkModulePath)) {
    $JlinkHelp = & $Jlink --help 2>&1
    if ($LASTEXITCODE -ne 0 -or
            ($JlinkHelp -join "`n") -notmatch "Linking from run-time image enabled") {
        throw "Java must provide JMODs or be built with --enable-linkable-runtime"
    }
    $JdkModulePath = $null
}
$JavaVersionLine = Get-Content $Release |
    Where-Object { $_ -match "^JAVA_VERSION=" } |
    Select-Object -First 1
$JavaVersion = ([string] $JavaVersionLine -replace "^JAVA_VERSION=", "").Trim('"')
if ($JavaVersion -notmatch "^([0-9]+)([.+-].*)?$") {
    throw "Unable to determine the Java feature version from $JavaVersion"
}
$JavaFeature = [int] $Matches[1]
if ($JavaFeature -lt 25) {
    throw "Java 25 or later is required, found $JavaVersion"
}

if ([string]::IsNullOrWhiteSpace($Output)) {
    $UserHome = [Environment]::GetFolderPath([Environment+SpecialFolder]::UserProfile)
    $Output = Join-Path $UserHome ".jdks\ja-$JavaFeature"
}
if (Test-Path -LiteralPath $Output) {
    throw "Output path already exists: $Output"
}

if (-not [string]::IsNullOrWhiteSpace($env:JA_BIN_HOME)) {
    $JaBinHome = $env:JA_BIN_HOME
} elseif (-not [string]::IsNullOrWhiteSpace($env:XDG_BIN_HOME)) {
    $JaBinHome = $env:XDG_BIN_HOME
} elseif (-not [string]::IsNullOrWhiteSpace($env:XDG_DATA_HOME)) {
    $JaBinHome = Join-Path (Split-Path -Parent $env:XDG_DATA_HOME) "bin"
} else {
    $UserHome = [Environment]::GetFolderPath([Environment+SpecialFolder]::UserProfile)
    $JaBinHome = Join-Path $UserHome ".local\bin"
}
$DirectorySeparators = [char[]] @('\', '/')
$NormalizedJaBinHome = $JaBinHome.TrimEnd($DirectorySeparators)
$JaBinOnPath = $false
foreach ($Entry in ($env:Path -split [IO.Path]::PathSeparator)) {
    if ($Entry.Trim().TrimEnd($DirectorySeparators) -ieq $NormalizedJaBinHome) {
        $JaBinOnPath = $true
        break
    }
}

$Work = Join-Path ([IO.Path]::GetTempPath()) "ja-install-$([Guid]::NewGuid())"
New-Item -ItemType Directory -Path $Work | Out-Null
try {
    $JigHome = Join-Path $Work "home"
    New-Item -ItemType Directory -Path $JigHome | Out-Null

    $Jig = Join-Path $Work "com.netflix.tools.jig-$JigVersion.jar"
    Invoke-WebRequest -OutFile $Jig `
        "https://repo.maven.apache.org/maven2/com/netflix/com.netflix.tools.jig/$JigVersion/com.netflix.tools.jig-$JigVersion.jar"

    $JigArguments = @(
        "-Duser.home=$JigHome",
        "--upgrade-module-path", $Jig,
        "--module", "com.netflix.tools.jig/com.netflix.tools.jig.Jig"
    )
    if ([string]::IsNullOrWhiteSpace($JaVersion)) {
        $JaVersion = & $Java @JigArguments `
            --list-module-versions com.netflix.tools.ja | Select-Object -Last 1
        if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($JaVersion)) {
            throw "Unable to determine the latest Ja version"
        }
    }

    $ResolvedArguments = @(& $Java @JigArguments `
        --add-requires "com.netflix.tools.ja@$JaVersion" `
        --prefer-jmod `
        --target-platform CURRENT `
        --compile-time `
        --resolve-options module-path,upgrade-module-path)
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to resolve Ja"
    }
    $ModulePath = $null
    $UpgradeModulePath = $null
    for ($Index = 0; $Index -lt $ResolvedArguments.Count; $Index += 2) {
        if ($Index + 1 -ge $ResolvedArguments.Count) {
            throw "Jig returned an option without a value: $($ResolvedArguments[$Index])"
        }
        switch ($ResolvedArguments[$Index]) {
            "--module-path" { $ModulePath = $ResolvedArguments[$Index + 1] }
            "--upgrade-module-path" { $UpgradeModulePath = $ResolvedArguments[$Index + 1] }
            default { throw "Unexpected Jig argument: $($ResolvedArguments[$Index])" }
        }
    }
    $ModulePathEntries = @()
    if (-not [string]::IsNullOrWhiteSpace($UpgradeModulePath)) {
        $ModulePathEntries += $UpgradeModulePath
    }
    if (-not [string]::IsNullOrWhiteSpace($JdkModulePath)) {
        $ModulePathEntries += $JdkModulePath
    }
    if (-not [string]::IsNullOrWhiteSpace($ModulePath)) {
        $ModulePathEntries += $ModulePath
    }
    if ($ModulePathEntries.Count -eq 0) {
        throw "Jig did not provide the module path required by jlink"
    }

    $OutputParent = Split-Path -Parent $Output
    if (-not [string]::IsNullOrEmpty($OutputParent)) {
        New-Item -ItemType Directory -Force -Path $OutputParent | Out-Null
    }

    $LinkArguments = @(
        "--module-path", ($ModulePathEntries -join [IO.Path]::PathSeparator),
        "--add-modules", "ALL-MODULE-PATH"
    )
    if (-not $OpenJ9) {
        $LinkArguments += "--generate-cds-archive"
    }
    $LinkArguments += @("--output", $Output)
    & $Jlink @LinkArguments
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to link the Ja JDK"
    }
    Copy-Item -LiteralPath $Sources -Destination (Join-Path $Output "lib\src.zip")

    if (-not [string]::IsNullOrWhiteSpace($JdkModulePath)) {
        $OutputJmods = Join-Path $Output "jmods"
        New-Item -ItemType Directory -Path $OutputJmods | Out-Null
        Copy-Item -Path (Join-Path $JdkModulePath "*.jmod") -Destination $OutputJmods
        Get-ChildItem -Path $JigHome -Recurse -File -Filter "*.jmod" |
            Copy-Item -Destination $OutputJmods
    }
    $OutputModules = Join-Path $Output "lib\ja\modules"
    New-Item -ItemType Directory -Force -Path $OutputModules | Out-Null
    Get-ChildItem -Path $JigHome -Recurse -File -Filter "*.jar" |
        Copy-Item -Destination $OutputModules
    if ($OpenJ9) {
        $LauncherConfiguration = Join-Path $Output "conf\com.netflix.tools.launcher"
        foreach ($Options in Get-ChildItem -Path $LauncherConfiguration -Filter "*.args") {
            if ((Get-Content $Options.FullName) -notcontains "-L-aot=auto") {
                continue
            }
            $CommandName = [IO.Path]::GetFileNameWithoutExtension($Options.Name)
            $Command = Join-Path $Output "bin\$CommandName.exe"
            & $Command "-L-aot=create" "--version" | Out-Null
            if ($LASTEXITCODE -ne 0) {
                throw "Unable to warm shared class cache with $CommandName"
            }
        }
    }

    Write-Output "Ja $JaVersion installed in $Output"
    Write-Output ""
    Write-Output "To use Ja in this PowerShell session:"
    Write-Output ""
    $PathEntries = @((Join-Path $Output "bin"))
    if (-not $JaBinOnPath) {
        $PathEntries += $JaBinHome
    }
    $PathPrefix = ($PathEntries -join [IO.Path]::PathSeparator) + [IO.Path]::PathSeparator
    if ($SourceFromJavaHome) {
        $QuotedOutput = "'" + $Output.Replace("'", "''") + "'"
        Write-Output "  `$env:JAVA_HOME = $QuotedOutput"
    }
    $QuotedPathPrefix = "'" + $PathPrefix.Replace("'", "''") + "'"
    Write-Output "  `$env:Path = $QuotedPathPrefix + `$env:Path"
} finally {
    Remove-Item -Recurse -Force $Work
}
