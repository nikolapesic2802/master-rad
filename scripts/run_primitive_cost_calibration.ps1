param(
    [string]$OutputDir = "benchmarks/runs/primitive-cost-calibration",
    [switch]$ConfirmMeasurements
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

if (-not $ConfirmMeasurements) {
    throw "GPU calibration requires -ConfirmMeasurements."
}

$ProjectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$RunsRoot = [IO.Path]::GetFullPath(
    (Join-Path $ProjectRoot "benchmarks/runs")).TrimEnd('\', '/')
$ResolvedOutput = if ([IO.Path]::IsPathRooted($OutputDir)) {
    [IO.Path]::GetFullPath($OutputDir)
} else {
    [IO.Path]::GetFullPath((Join-Path $ProjectRoot $OutputDir))
}
$RunsPrefix = $RunsRoot + [IO.Path]::DirectorySeparatorChar
if (-not $ResolvedOutput.StartsWith(
        $RunsPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw "OutputDir must be a new directory inside benchmarks/runs."
}
if (Test-Path -LiteralPath $ResolvedOutput) {
    throw "Refusing to overwrite calibration output: $ResolvedOutput"
}

foreach ($Name in @("_JAVA_OPTIONS", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS")) {
    if (-not [string]::IsNullOrWhiteSpace(
            [Environment]::GetEnvironmentVariable($Name))) {
        throw "$Name must be unset for calibration."
    }
}

function Write-NewUtf8File {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Content
    )
    $Normalized = $Content.TrimEnd([char]13, [char]10) + [Environment]::NewLine
    $Bytes = [Text.UTF8Encoding]::new($false).GetBytes($Normalized)
    $Stream = [IO.File]::Open(
        $Path, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    try {
        $Stream.Write($Bytes, 0, $Bytes.Length)
    } finally {
        $Stream.Dispose()
    }
}

function Read-OneGpu {
    $Command = Get-Command "nvidia-smi.exe" -ErrorAction SilentlyContinue
    if ($null -eq $Command) {
        $Command = Get-Command "nvidia-smi" -ErrorAction Stop
    }
    $Arguments = @(
        "--query-gpu=name,uuid,compute_cap,driver_version",
        "--format=csv,noheader,nounits"
    )
    $Rows = @(& $Command.Source @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0 -or $Rows.Count -ne 1) {
        throw "Calibration requires exactly one visible NVIDIA GPU."
    }
    $Fields = @("$($Rows[0])" -split ",\s*", 4)
    if ($Fields.Count -ne 4) {
        throw "Could not read the NVIDIA GPU identity."
    }
    return [PSCustomObject]@{
        name = $Fields[0].Trim()
        uuid = $Fields[1].Trim()
        computeCapability = "compute_" + $Fields[2].Trim().Replace(".", "")
        driverVersion = $Fields[3].Trim()
    }
}

Push-Location $ProjectRoot
try {
    $Status = @(& git status --porcelain=v1 --untracked-files=all)
    if ($LASTEXITCODE -ne 0 -or $Status.Count -ne 0) {
        throw "Calibration requires a clean source tree."
    }
    $SourceCommit = (& git rev-parse HEAD).Trim().ToLowerInvariant()
    $SourceTree = (& git rev-parse 'HEAD^{tree}').Trim().ToLowerInvariant()

    $ProtocolPath = Join-Path $ProjectRoot "benchmarks/config/primitive-cost-protocol.json"
    $ProtocolRaw = [IO.File]::ReadAllText($ProtocolPath, [Text.Encoding]::UTF8)
    $Protocol = $ProtocolRaw | ConvertFrom-Json
    $ExpectedOperations = @(
        "sphere", "box", "affineSphere", "affineBox",
        "plane", "nodeAabb", "interiorTraversal"
    )
    if ([int]$Protocol.schemaVersion -ne 1 -or
            "$($Protocol.study)" -ne "primitive-cost-calibration" -or
            [int]$Protocol.contexts -ne 5 -or
            (@($Protocol.operations) -join ",") -ne ($ExpectedOperations -join ",")) {
        throw "The primitive-cost protocol document is not the supported protocol."
    }
    $ExpectedIntegers = [ordered]@{
        workloadRecords = 4096
        rayProfiles = 3
        blocks = 256
        threadsPerBlock = 256
        iterationsPerThread = 2048
        operationCopiesPerIteration = 8
        sublaunchesPerObservation = 32
        warmupPairsPerOperation = 4
        measuredPairsPerOperation = 12
    }
    foreach ($Entry in $ExpectedIntegers.GetEnumerator()) {
        if ([int]$Protocol.($Entry.Key) -ne [int]$Entry.Value) {
            throw "Unexpected protocol value for $($Entry.Key)."
        }
    }
    $ProtocolSha256 = (
        Get-FileHash -Algorithm SHA256 -LiteralPath $ProtocolPath
    ).Hash.ToLowerInvariant()

    & "$PSScriptRoot\compile.ps1" -OutputDir "out/calibration-classes"
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
    $Classes = Join-Path $ProjectRoot "out/calibration-classes"
    $Classpath = "$Classes;$(Join-Path $ProjectRoot 'lib\*')"
    $IdentityArguments = @(
        "-cp", $Classpath,
        "xyz.marsavic.gfxlab.benchmark.BenchmarkClassIdentity"
    )
    $CompiledClassesSha256 = (& java @IdentityArguments).Trim()
    if ($LASTEXITCODE -ne 0 -or
            $CompiledClassesSha256 -notmatch '^[0-9a-f]{64}$') {
        throw "Could not compute the compiled-class identity."
    }

    $Gpu = Read-OneGpu
    if ($Gpu.name -cne "$($Protocol.gpu.name)" -or
            $Gpu.computeCapability -cne "$($Protocol.gpu.computeCapability)" -or
            $Gpu.driverVersion -cne "$($Protocol.gpu.driverVersion)") {
        throw "The visible GPU identity differs from the calibration protocol."
    }

    $Mutex = [Threading.Mutex]::new(
        $false, "Global\GfxLabThesisGpuBenchmark")
    $Acquired = $false
    try {
        $Acquired = $Mutex.WaitOne(0)
        if (-not $Acquired) {
            throw "Another primitive-cost calibration is running."
        }
        if (Test-Path -LiteralPath $ResolvedOutput) {
            throw "Refusing to overwrite calibration output: $ResolvedOutput"
        }
        New-Item -ItemType Directory -Path $ResolvedOutput | Out-Null

        $Manifest = [ordered]@{
            schemaVersion = 1
            createdAt = [DateTimeOffset]::Now.ToString("o")
            sourceCommit = $SourceCommit
            sourceTree = $SourceTree
            compiledClassesSha256 = $CompiledClassesSha256
            protocolPath = "benchmarks/config/primitive-cost-protocol.json"
            protocolSha256 = $ProtocolSha256
            gpu = [ordered]@{
                name = $Gpu.name
                uuid = $Gpu.uuid
                computeCapability = $Gpu.computeCapability
                driverVersion = $Gpu.driverVersion
                deviceIndex = 0
            }
        }
        $ManifestPath = Join-Path $ResolvedOutput "run-manifest.json"
        Write-NewUtf8File -Path $ManifestPath -Content (
            $Manifest | ConvertTo-Json -Depth 5)
        $ManifestSha256 = (
            Get-FileHash -Algorithm SHA256 -LiteralPath $ManifestPath
        ).Hash.ToLowerInvariant()

        for ($Context = 1; $Context -le 5; $Context++) {
            $ContextPath = Join-Path $ResolvedOutput (
                "context-{0:D2}.json" -f $Context)
            $BenchmarkArguments = @(
                "-cp", $Classpath,
                "xyz.marsavic.gfxlab.gpu.PrimitiveCostBenchmark",
                $ContextPath, "$Context", $ManifestSha256
            )
            & java @BenchmarkArguments
            if ($LASTEXITCODE -ne 0) {
                exit $LASTEXITCODE
            }
        }

        & "$PSScriptRoot\summarize_primitive_costs.ps1" -RunDir $ResolvedOutput
        if ($LASTEXITCODE -ne 0) {
            exit $LASTEXITCODE
        }
    } finally {
        if ($Acquired) {
            $Mutex.ReleaseMutex()
        }
        $Mutex.Dispose()
    }
} finally {
    Pop-Location
}
