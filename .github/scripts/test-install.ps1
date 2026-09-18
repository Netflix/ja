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
    [string] $JaVersion
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Condition {
    param(
        [bool] $Condition,
        [string] $Message
    )

    if (-not $Condition) {
        throw $Message
    }
}

if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    throw "JAVA_HOME must identify the source JDK"
}
$SourceJavaHome = $env:JAVA_HOME
$Release = Join-Path $SourceJavaHome "release"
Assert-Condition (Test-Path -LiteralPath $Release -PathType Leaf) `
    "JAVA_HOME must identify the source JDK"

$JavaVersionLine = Get-Content -LiteralPath $Release |
    Where-Object { $_ -match "^JAVA_VERSION=" } |
    Select-Object -First 1
$JavaVersion = ([string] $JavaVersionLine -replace "^JAVA_VERSION=", "").Trim('"')
Assert-Condition ($JavaVersion -match "^([0-9]+)([.+-].*)?$") `
    "Unable to determine the Java feature version from $JavaVersion"
$JavaFeature = [int] $Matches[1]

$Java = Join-Path $SourceJavaHome "bin\java.exe"
$JavaProperties = & $Java -XshowSettings:properties -version 2>&1
Assert-Condition ($LASTEXITCODE -eq 0) "Unable to inspect the source JDK"
$JavaVmName = $JavaProperties |
    Where-Object { [string] $_ -match "^\s*java\.vm\.name = (.+)\s*$" } |
    ForEach-Object { $Matches[1].Trim() } |
    Select-Object -First 1
$ExpectedJavaVm = if ([string]::IsNullOrWhiteSpace($env:EXPECTED_JAVA_VM)) {
    "HotSpot"
} else {
    $env:EXPECTED_JAVA_VM
}
switch ($ExpectedJavaVm) {
    "HotSpot" {
        Assert-Condition ($JavaVmName -notmatch "OpenJ9") `
            "Expected a HotSpot source JDK, found $JavaVmName"
    }
    "OpenJ9" {
        Assert-Condition ($JavaVmName -match "OpenJ9") `
            "Expected an OpenJ9 source JDK, found $JavaVmName"
    }
    default { throw "Unsupported expected Java VM: $ExpectedJavaVm" }
}

$ExpectedLinkMode = if ([string]::IsNullOrWhiteSpace($env:EXPECTED_LINK_MODE)) {
    "jmods"
} else {
    $env:EXPECTED_LINK_MODE
}
switch ($ExpectedLinkMode) {
    "jmods" {
        Assert-Condition (Test-Path -LiteralPath (Join-Path $SourceJavaHome "jmods\java.base.jmod") `
                -PathType Leaf) "The source JDK does not contain JMODs"
    }
    default { throw "Unsupported expected link mode on Windows: $ExpectedLinkMode" }
}

$UserHome = [Environment]::GetFolderPath([Environment+SpecialFolder]::UserProfile)
$JaHome = Join-Path $UserHome ".jdks\ja-$JavaFeature"
Assert-Condition (-not (Test-Path -LiteralPath $JaHome)) `
    "Test installation path already exists: $JaHome"

$Work = Join-Path ([IO.Path]::GetTempPath()) "ja-install-test-$([Guid]::NewGuid())"
New-Item -ItemType Directory -Path $Work | Out-Null
try {
    $Stdout = Join-Path $Work "stdout"
    $Stderr = Join-Path $Work "stderr"
    $InstallArguments = @{}
    if (-not [string]::IsNullOrWhiteSpace($JaVersion)) {
        $InstallArguments["JaVersion"] = $JaVersion
    }
    try {
        & (Join-Path $PSScriptRoot "..\..\install.ps1") @InstallArguments > $Stdout 2> $Stderr
    } catch {
        if (Test-Path -LiteralPath $Stdout) {
            Get-Content -LiteralPath $Stdout | Write-Host
        }
        if (Test-Path -LiteralPath $Stderr) {
            Get-Content -LiteralPath $Stderr | Write-Host
        }
        throw
    }

    $InstalledJava = Join-Path $JaHome "bin\java.exe"
    $InstalledJa = Join-Path $JaHome "bin\ja.exe"
    Assert-Condition (Test-Path -LiteralPath $InstalledJava -PathType Leaf) `
        "The installed JDK does not contain java.exe"
    Assert-Condition (Test-Path -LiteralPath $InstalledJa -PathType Leaf) `
        "The installed JDK does not contain ja.exe"

    $InstalledModule = & $InstalledJava --describe-module com.netflix.tools.ja |
        Select-Object -First 1
    Assert-Condition ($LASTEXITCODE -eq 0) "Unable to describe the installed ja module"
    $InstalledVersion = ([string] $InstalledModule -replace "^com\.netflix\.tools\.ja@", "")
    if (-not [string]::IsNullOrWhiteSpace($JaVersion)) {
        Assert-Condition ($InstalledVersion -eq $JaVersion) `
            "Expected ja $JaVersion, found $InstalledModule"
    }

    $JaVersionOutput = & $InstalledJa --version
    Assert-Condition ($LASTEXITCODE -eq 0) "The installed ja command failed"
    Assert-Condition (($JaVersionOutput -join "`n") -eq "ja $InstalledVersion") `
        "Unexpected ja version output: $($JaVersionOutput -join ' ')"

    Assert-Condition (Test-Path -LiteralPath (Join-Path $JaHome "lib\src.zip") -PathType Leaf) `
        "The installed JDK does not contain lib\src.zip"
    $InstalledModules = & $InstalledJava --list-modules
    Assert-Condition ($LASTEXITCODE -eq 0) "Unable to list installed modules"
    Assert-Condition ($InstalledModules -contains "com.netflix.tools.ja@$InstalledVersion") `
        "The installed runtime does not contain ja $InstalledVersion"

    $InstalledProperties = & $InstalledJava -XshowSettings:properties -version 2>&1
    Assert-Condition ($LASTEXITCODE -eq 0) "Unable to inspect the installed JDK"
    $InstalledVmName = $InstalledProperties |
        Where-Object { [string] $_ -match "^\s*java\.vm\.name = (.+)\s*$" } |
        ForEach-Object { $Matches[1].Trim() } |
        Select-Object -First 1
    if ($ExpectedJavaVm -eq "OpenJ9") {
        Assert-Condition ($InstalledVmName -match "OpenJ9") `
            "Expected an installed OpenJ9 JDK, found $InstalledVmName"
    } else {
        Assert-Condition ($InstalledVmName -notmatch "OpenJ9") `
            "Expected an installed HotSpot JDK, found $InstalledVmName"
        $CdsArchives = @(Get-ChildItem -Path (Join-Path $JaHome "lib") -Recurse -File -Filter "*.jsa")
        Assert-Condition ($CdsArchives.Count -gt 0) "The installed JDK does not contain a CDS archive"
    }

    $InstalledJmods = @(Get-ChildItem -Path (Join-Path $JaHome "jmods") -File -Filter "*.jmod")
    Assert-Condition ($InstalledJmods.Count -gt 0) "The installed JDK does not contain JMODs"
    $InstalledJmod = Join-Path $JaHome "bin\jmod.exe"
    foreach ($Jmod in $InstalledJmods) {
        $Description = & $InstalledJmod describe $Jmod.FullName | Select-Object -First 1
        Assert-Condition ($LASTEXITCODE -eq 0) "Unable to describe $($Jmod.FullName)"
        $ModuleName = ([string] $Description -split "@", 2)[0]
        Assert-Condition ($Jmod.Name -eq "$ModuleName.jmod") `
            "Retained JMOD is not named for its module: $($Jmod.FullName)"
    }

    $InstallerOutput = Get-Content -LiteralPath $Stdout
    Assert-Condition ($InstallerOutput -contains "ja $InstalledVersion installed in $JaHome") `
        "The installer did not report the installed ja version and location"
} finally {
    Remove-Item -LiteralPath $JaHome -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $Work -Recurse -Force -ErrorAction SilentlyContinue
}
