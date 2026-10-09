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
    [string] $JigVersion = "0.16.3",
    [string] $JaVersion,
    [ValidateSet("Jdk", "Standalone")]
    [string] $Installation = "Jdk",
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
$Javac = Join-Path $SourceJavaHome "bin\javac.exe"
$Jlink = Join-Path $SourceJavaHome "bin\jlink.exe"
$Sources = Join-Path $SourceJavaHome "lib\src.zip"
if (-not (Test-Path -PathType Leaf $Java) -or
        -not (Test-Path -PathType Leaf $Javac) -or
        -not (Test-Path -PathType Leaf $Release)) {
    throw "Java must be a JDK 25 or later installation with java, javac, and a release file"
}
$JdkModulePath = $null
if ($Installation -eq "Jdk") {
    if (-not (Test-Path -PathType Leaf $Jlink) -or
            -not (Test-Path -PathType Leaf $Sources)) {
        throw "The development JDK installation requires jlink and lib/src.zip"
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

$UserHome = [Environment]::GetFolderPath([Environment+SpecialFolder]::UserProfile)

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
            throw "Unable to determine the latest ja version"
        }
    }

    if ($Installation -eq "Standalone") {
        if ([string]::IsNullOrWhiteSpace($Output)) {
            if (-not [string]::IsNullOrWhiteSpace($env:JA_INSTALL_HOME)) {
                $Applications = $env:JA_INSTALL_HOME
            } elseif (-not [string]::IsNullOrWhiteSpace($env:XDG_DATA_HOME)) {
                $Applications = Join-Path $env:XDG_DATA_HOME "com.netflix.tools.ja"
            } else {
                $LocalApplicationData = $env:LOCALAPPDATA
                if ([string]::IsNullOrWhiteSpace($LocalApplicationData)) {
                    $LocalApplicationData = $UserHome
                }
                $Applications = Join-Path $LocalApplicationData "Programs\com.netflix.tools.ja"
            }
            $Output = Join-Path $Applications "com.netflix.tools.ja@$JaVersion"
        }
        if (Test-Path -LiteralPath $Output) {
            throw "Output path already exists: $Output"
        }

        $Architecture = [Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString()
        switch ($Architecture) {
            "Arm64" { $Classifier = "windows-aarch_64" }
            "X64" { $Classifier = "windows-x86_64" }
            default { throw "Unsupported standalone Windows architecture: $Architecture" }
        }
        $Archive = Join-Path $Work "com.netflix.tools.ja-$JaVersion-$Classifier.zip"
        Invoke-WebRequest -OutFile $Archive `
            "https://repo.maven.apache.org/maven2/com/netflix/com.netflix.tools.ja/$JaVersion/com.netflix.tools.ja-$JaVersion-$Classifier.zip"
        $StagedStandalone = Join-Path $Work "standalone"
        Expand-Archive -LiteralPath $Archive -DestinationPath $StagedStandalone
        $StagedEntrypoint = Join-Path $StagedStandalone "bin\ja.exe"
        $StagedDispatcher = Join-Path $StagedStandalone "lib\com.netflix.tools.launcher\dispatcher.exe"
        if (-not (Test-Path -PathType Leaf $StagedEntrypoint) -or
                -not (Test-Path -PathType Leaf $StagedDispatcher)) {
            throw "The standalone archive does not contain ja and dispatcher commands"
        }
        $Command = Join-Path $JaBinHome "ja.exe"
        $Current = Join-Path $JaBinHome "ja.current"
        if (Test-Path -LiteralPath $Command) {
            if (-not (Test-Path -PathType Leaf $Current)) {
                throw "Command already exists and is not managed by ja: $Command"
            }
            $Configured = (Get-Content -LiteralPath $Current -TotalCount 1).Trim()
            $ConfiguredHome = Split-Path -Parent (Split-Path -Parent $Configured)
            if ([string]::IsNullOrWhiteSpace($ConfiguredHome) -or
                    -not (Test-Path -PathType Leaf (Join-Path $ConfiguredHome "app\modules.hash")) -or
                    -not (Test-Path -PathType Leaf (Join-Path $ConfiguredHome "conf\com.netflix.tools.launcher\ja.args"))) {
                throw "Command is owned by another installation: $Command"
            }
        }

        $OutputParent = Split-Path -Parent $Output
        if (-not [string]::IsNullOrEmpty($OutputParent)) {
            New-Item -ItemType Directory -Force -Path $OutputParent | Out-Null
        }
        Move-Item -LiteralPath $StagedStandalone -Destination $Output
        $Entrypoint = Join-Path $Output "bin\ja.exe"
        $Dispatcher = Join-Path $Output "lib\com.netflix.tools.launcher\dispatcher.exe"
        New-Item -ItemType Directory -Force -Path $JaBinHome | Out-Null
        $TemporaryCommand = Join-Path $JaBinHome ".ja-launcher-$PID.exe"
        $TemporaryCurrent = Join-Path $JaBinHome ".ja-current-$PID"
        Copy-Item -LiteralPath $Dispatcher -Destination $TemporaryCommand
        [IO.File]::WriteAllText(
            $TemporaryCurrent,
            $Entrypoint + [Environment]::NewLine,
            [Text.UTF8Encoding]::new($false))
        Move-Item -Force -LiteralPath $TemporaryCommand -Destination $Command
        Move-Item -Force -LiteralPath $TemporaryCurrent -Destination $Current

        Write-Output "Ja $JaVersion standalone distribution installed in $Output"
        if (-not $JaBinOnPath) {
            Write-Output ""
            Write-Output "To use Ja in this PowerShell session:"
            Write-Output ""
            $QuotedBin = "'" + $JaBinHome.Replace("'", "''") + "'"
            Write-Output "  `$env:Path = $QuotedBin + ';' + `$env:Path"
        }
        Write-Output ""
        Write-Output "After activating ja, enable completions in this PowerShell session with:"
        Write-Output ""
        Write-Output "  ja completion powershell | Out-String | Invoke-Expression"
        Write-Output ""
        Write-Output "Add these commands to your PowerShell profile to use Ja in future sessions."
        return
    }

    if ([string]::IsNullOrWhiteSpace($Output)) {
        $Output = Join-Path $UserHome ".jdks\ja-$JavaFeature"
    }
    if (Test-Path -LiteralPath $Output) {
        throw "Output path already exists: $Output"
    }

    $ResolvedArguments = @(& $Java @JigArguments `
        --add-requires "com.netflix.tools.ja@$JaVersion" `
        --add-modules "com.netflix.tools.jfmt,com.netflix.tools.jist,com.netflix.tools.jdocserver" `
        --prefer-jmod `
        --target-platform CURRENT `
        --resolve-options module-path,upgrade-module-path)
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to resolve ja"
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
    $ResolvedModulePathEntries = @()
    foreach ($PathList in @($UpgradeModulePath, $ModulePath)) {
        if (-not [string]::IsNullOrWhiteSpace($PathList)) {
            $ResolvedModulePathEntries += $PathList -split [Regex]::Escape([IO.Path]::PathSeparator)
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
        throw "Unable to link the ja-enabled JDK"
    }
    Copy-Item -LiteralPath $Sources -Destination (Join-Path $Output "lib\src.zip")

    if (-not [string]::IsNullOrWhiteSpace($JdkModulePath)) {
        $OutputJmods = Join-Path $Output "jmods"
        New-Item -ItemType Directory -Path $OutputJmods | Out-Null
        Copy-Item -Path (Join-Path $JdkModulePath "*.jmod") -Destination $OutputJmods
        foreach ($ResolvedModule in $ResolvedModulePathEntries) {
            if ([IO.Path]::GetExtension($ResolvedModule) -ne ".jmod" -or
                    -not (Test-Path -LiteralPath $ResolvedModule -PathType Leaf)) {
                continue
            }
            $ModuleName = ([IO.Path]::GetFileNameWithoutExtension($ResolvedModule) -split "-", 2)[0]
            Copy-Item -LiteralPath $ResolvedModule `
                -Destination (Join-Path $OutputJmods "$ModuleName.jmod") -Force
        }
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
            & $Command "-L-aot=create" "--help" | Out-Null
            if ($LASTEXITCODE -ne 0) {
                throw "Unable to warm shared class cache with $CommandName"
            }
        }
    }

    Write-Output "ja $JaVersion installed in $Output"
    Write-Output ""
    Write-Output "To use ja in this PowerShell session:"
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
    Write-Output ""
    Write-Output "After activating ja, enable completions in this PowerShell session with:"
    Write-Output ""
    Write-Output "  ja completion powershell | Out-String | Invoke-Expression"
    Write-Output ""
    Write-Output "Add this command to your PowerShell profile to enable completions in future sessions."
} finally {
    Remove-Item -Recurse -Force $Work
}
