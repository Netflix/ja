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
    [Parameter(Mandatory = $true)]
    [string] $Archive,
    [Parameter(Mandatory = $true)]
    [string] $JaVersion
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Condition {
    param([bool] $Condition, [string] $Message)
    if (-not $Condition) {
        throw $Message
    }
}

Assert-Condition (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) `
    "JAVA_HOME must identify the source JDK"
$SourceJavaHome = $env:JAVA_HOME
$Archive = (Resolve-Path -LiteralPath $Archive).Path
$Work = Join-Path ([IO.Path]::GetTempPath()) "ja-standalone-install-test-$([Guid]::NewGuid())"
$Applications = Join-Path $Work "applications"
$Commands = Join-Path $Work "bin"
$JaHome = Join-Path $Applications "com.netflix.tools.ja@$JaVersion"
New-Item -ItemType Directory -Path $Work | Out-Null
try {
    $env:JA_INSTALL_HOME = $Applications
    $env:JA_BIN_HOME = $Commands
    $env:JA_STANDALONE_ARCHIVE = $Archive
    $Stdout = Join-Path $Work "stdout"
    $Stderr = Join-Path $Work "stderr"
    & (Join-Path $PSScriptRoot "..\..\install.ps1") `
        -Installation Standalone -JaVersion $JaVersion > $Stdout 2> $Stderr

    Assert-Condition (-not (Test-Path -LiteralPath $Stderr) -or
            (Get-Item -LiteralPath $Stderr).Length -eq 0) `
        "The standalone installer wrote to standard error"
    $InstallerOutput = Get-Content -LiteralPath $Stdout
    Assert-Condition ($InstallerOutput -contains
            "Ja $JaVersion standalone distribution installed in $JaHome") `
        "The installer did not report the standalone version and location"

    $Entrypoint = Join-Path $JaHome "bin\ja.exe"
    $Dispatcher = Join-Path $JaHome "lib\com.netflix.tools.launcher\dispatcher.exe"
    $Command = Join-Path $Commands "ja.exe"
    $Current = Join-Path $Commands "ja.current"
    Assert-Condition (Test-Path -LiteralPath $Entrypoint -PathType Leaf) `
        "The standalone installation does not contain ja.exe"
    Assert-Condition (Test-Path -LiteralPath $Dispatcher -PathType Leaf) `
        "The standalone installation does not contain dispatcher.exe"
    Assert-Condition (Test-Path -LiteralPath (Join-Path $JaHome "app\modules.hash") -PathType Leaf) `
        "The standalone installation does not contain its module hash"
    Assert-Condition (-not (Test-Path -LiteralPath (Join-Path $JaHome "bin\java.exe"))) `
        "The standalone installation unexpectedly contains java.exe"
    Assert-Condition (Test-Path -LiteralPath $Command -PathType Leaf) `
        "The standalone command was not activated"
    Assert-Condition ((Get-Content -LiteralPath $Current -TotalCount 1).Trim() -eq $Entrypoint) `
        "The standalone command target is incorrect"

    $JavaHomeVersion = & $Command --version
    Assert-Condition ($LASTEXITCODE -eq 0 -and ($JavaHomeVersion -join "`n") -eq "ja $JaVersion") `
        "The standalone command failed with JAVA_HOME"
    Remove-Item Env:JAVA_HOME
    $env:Path = "$Commands$([IO.Path]::PathSeparator)$(Join-Path $SourceJavaHome 'bin')"
    $PathVersion = & $Command --version
    Assert-Condition ($LASTEXITCODE -eq 0 -and ($PathVersion -join "`n") -eq "ja $JaVersion") `
        "The standalone command failed with java on PATH"
} finally {
    Remove-Item -LiteralPath $Work -Recurse -Force -ErrorAction SilentlyContinue
}
