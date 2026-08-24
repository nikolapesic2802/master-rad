param(
    [Parameter(Mandatory = $true)]
    [ValidateSet(
        "SelfTest", "Correctness", "Construction",
        "EvaluationPreflight", "EvaluationMeasure", "EvaluationFinalize",
        "LeafPreflight", "LeafMeasure", "LeafFinalize",
        "DepthPreflight", "DepthMeasure", "DepthRender", "DepthFinalize"
    )]
    [string]$Stage,
    [string]$OutputRoot,
    [ValidateRange(-1, 78)]
    [int]$Index = -1,
    [switch]$ConfirmMeasurements
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$script:ProjectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$script:ClassesRelative = "out/thesis-classes"
$script:Classes = Join-Path $script:ProjectRoot $script:ClassesRelative
$script:Classpath = "$script:Classes;$(Join-Path $script:ProjectRoot 'lib\*')"
$script:EvaluationClass = "xyz.marsavic.gfxlab.benchmark.EvaluationStudy"
$script:LeafClass = "xyz.marsavic.gfxlab.benchmark.LeafSizeStudy"
$script:DepthClass = "xyz.marsavic.gfxlab.benchmark.DepthStudy"
$script:ConstructionClass = "xyz.marsavic.gfxlab.benchmark.ConstructionStudy"
$script:CorrectnessClass = "xyz.marsavic.gfxlab.benchmark.GpuBvhCorrectnessCheck"
$script:ProtocolCheckClass = "xyz.marsavic.gfxlab.benchmark.ProtocolCheck"
$script:ClassIdentityClass = "xyz.marsavic.gfxlab.benchmark.BenchmarkClassIdentity"

function Assert-NoJavaOptionInjection {
    foreach ($Name in @("_JAVA_OPTIONS", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS")) {
        if (-not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($Name))) {
            throw "$Name must be unset for benchmark runs."
        }
    }
}

function Resolve-RunRoot {
    param([Parameter(Mandatory = $true)][string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { throw "$Stage requires -OutputRoot." }
    $Resolved = if ([IO.Path]::IsPathRooted($Value)) {
        [IO.Path]::GetFullPath($Value)
    } else {
        [IO.Path]::GetFullPath((Join-Path $script:ProjectRoot $Value))
    }
    $RunsRoot = [IO.Path]::GetFullPath(
        (Join-Path $script:ProjectRoot "benchmarks/runs")).TrimEnd('\', '/')
    $Prefix = $RunsRoot + [IO.Path]::DirectorySeparatorChar
    if (-not $Resolved.StartsWith($Prefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "OutputRoot must be inside benchmarks/runs."
    }
    return $Resolved
}

function Get-GpuIdentity {
    $Command = Get-Command "nvidia-smi.exe" -ErrorAction SilentlyContinue
    if ($null -eq $Command) { $Command = Get-Command "nvidia-smi" -ErrorAction SilentlyContinue }
    if ($null -eq $Command) { throw "nvidia-smi is required for GPU measurements." }
    $Rows = @(& $Command.Source "--query-gpu=name,compute_cap,driver_version" `
        "--format=csv,noheader,nounits" 2>&1)
    if ($LASTEXITCODE -ne 0 -or $Rows.Count -ne 1) {
        throw "Exactly one NVIDIA GPU is required for the measurement campaign."
    }
    $Fields = @("$($Rows[0])" -split ",\s*", 3)
    if ($Fields.Count -ne 3) { throw "Could not read the NVIDIA GPU identity." }
    return [PSCustomObject]@{
        name = $Fields[0].Trim()
        computeCapability = "compute_" + $Fields[1].Trim().Replace(".", "")
        driverVersion = $Fields[2].Trim()
    }
}

function Get-CalibratedGpuProperties {
    $ProtocolPath = Join-Path $script:ProjectRoot `
        "benchmarks/config/primitive-cost-protocol.json"
    $Protocol = Get-Content -Raw -LiteralPath $ProtocolPath | ConvertFrom-Json
    $Gpu = Get-GpuIdentity
    if ($Gpu.name -cne "$($Protocol.gpu.name)" -or
            $Gpu.computeCapability -cne "$($Protocol.gpu.computeCapability)" -or
            $Gpu.driverVersion -cne "$($Protocol.gpu.driverVersion)") {
        throw "The GPU identity differs from the primitive-cost calibration protocol."
    }
    return @("-Dgfxlab.gpu.driverVersion=$($Gpu.driverVersion)")
}

function Invoke-Java {
    param(
        [Parameter(Mandatory = $true)][string]$ClassName,
        [string[]]$Arguments = @(),
        [string[]]$Properties = @()
    )
    & java @Properties -cp $script:Classpath $ClassName @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$ClassName failed with exit code $LASTEXITCODE." }
}

function Invoke-JavaWithTimeout {
    param(
        [Parameter(Mandatory = $true)][string]$ClassName,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string[]]$Properties,
        [Parameter(Mandatory = $true)][int]$TimeoutSeconds
    )
    $Java = Get-Command "java" -ErrorAction Stop
    $Info = [Diagnostics.ProcessStartInfo]::new()
    $Info.FileName = $Java.Source
    $Info.WorkingDirectory = $script:ProjectRoot
    $Info.UseShellExecute = $false
    foreach ($Argument in @($Properties + @("-cp", $script:Classpath, $ClassName) + $Arguments)) {
        [void]$Info.ArgumentList.Add("$Argument")
    }
    $Process = [Diagnostics.Process]::new()
    $Process.StartInfo = $Info
    try {
        if (-not $Process.Start()) { throw "Could not start $ClassName." }
        if (-not $Process.WaitForExit($TimeoutSeconds * 1000)) {
            $Process.Kill($true)
            throw "$ClassName exceeded its $TimeoutSeconds second limit."
        }
        if ($Process.ExitCode -ne 0) {
            throw "$ClassName failed with exit code $($Process.ExitCode)."
        }
    } finally {
        $Process.Dispose()
    }
}

function Invoke-WithGpuLock {
    param([Parameter(Mandatory = $true)][scriptblock]$Body)
    $Mutex = [Threading.Mutex]::new($false, "Global\GfxLabThesisGpuBenchmark")
    $Acquired = $false
    try {
        $Acquired = $Mutex.WaitOne(0)
        if (-not $Acquired) { throw "Another thesis GPU measurement is running." }
        & $Body
    } finally {
        if ($Acquired) { $Mutex.ReleaseMutex() }
        $Mutex.Dispose()
    }
}

Assert-NoJavaOptionInjection
$MeasurementStages = @("EvaluationMeasure", "LeafMeasure", "DepthMeasure", "DepthRender")
$IsMeasurement = $Stage -in $MeasurementStages
if ($IsMeasurement -ne [bool]$ConfirmMeasurements) {
    throw "-ConfirmMeasurements is required exactly for GPU measurement stages."
}
if ($Stage -eq "SelfTest") {
    if (-not [string]::IsNullOrWhiteSpace($OutputRoot) -or $Index -ne -1) {
        throw "SelfTest accepts no OutputRoot or Index."
    }
} elseif ([string]::IsNullOrWhiteSpace($OutputRoot)) {
    throw "$Stage requires -OutputRoot."
}
if ($Stage -notin $MeasurementStages -and $Index -ne -1) {
    throw "Only GPU measurement stages accept -Index."
}
if ($Stage -eq "EvaluationMeasure" -and ($Index -lt 0 -or $Index -gt 78)) {
    throw "EvaluationMeasure requires Index in [0,78]."
}
if ($Stage -eq "LeafMeasure" -and ($Index -lt 0 -or $Index -gt 2)) {
    throw "LeafMeasure requires Index in [0,2]."
}
if ($Stage -eq "DepthMeasure" -and $Index -ne 0) {
    throw "DepthMeasure requires Index 0."
}
if ($Stage -eq "DepthRender" -and $Index -ne -1) {
    throw "DepthRender accepts no Index."
}

Push-Location $script:ProjectRoot
try {
    & "$PSScriptRoot\compile.ps1" -OutputDir $script:ClassesRelative
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    foreach ($ClassFile in @(
        "BenchmarkProtocol.class", "ProtocolCheck.class", "ConstructionStudy.class",
        "EvaluationStudy.class", "LeafSizeStudy.class", "DepthStudy.class",
        "DepthPresentationStudy.class", "GpuBvhCorrectnessCheck.class"
    )) {
        $Path = Join-Path $script:Classes "xyz/marsavic/gfxlab/benchmark/$ClassFile"
        if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
            throw "Compiled benchmark classes are incomplete: $ClassFile"
        }
    }

    $BaseProperties = @(
        "-Xmx20g",
        "-Dgfxlab.projectRoot=$script:ProjectRoot",
        "-Dgfxlab.gpu.bvhStackSize=32",
        "-Dgfxlab.gpu.renderPixelsPerLaunch=65536",
        "-Dgfxlab.gpu.replayRaysPerLaunch=262144"
    )

    if ($Stage -eq "SelfTest") {
        Invoke-Java -ClassName $script:ProtocolCheckClass -Properties $BaseProperties
        python -B benchmarks/analyze_results.py --help | Out-Null
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        Write-Host "Benchmark self-test PASS"
        return
    }

    $Status = @(& git status --porcelain=v1 --untracked-files=all)
    if ($LASTEXITCODE -ne 0 -or $Status.Count -ne 0) {
        throw "Measurements require a clean source tree."
    }
    $SourceCommit = (& git rev-parse HEAD).Trim().ToLowerInvariant()
    $SourceTree = (& git rev-parse 'HEAD^{tree}').Trim().ToLowerInvariant()
    $CompiledClasses = (& java -cp $script:Classpath $script:ClassIdentityClass).Trim()
    if ($LASTEXITCODE -ne 0 -or $CompiledClasses -notmatch '^[0-9a-f]{64}$') {
        throw "Could not determine the compiled-class identity."
    }

    $RunsDirectory = Join-Path $script:ProjectRoot "benchmarks/runs"
    if (-not (Test-Path -LiteralPath $RunsDirectory -PathType Container)) {
        [void](New-Item -ItemType Directory -Path $RunsDirectory)
    }
    $Root = Resolve-RunRoot -Value $OutputRoot
    $Common = @(
        "--project-root", $script:ProjectRoot,
        "--output-root", $Root,
        "--compiled-classes-sha256", $CompiledClasses,
        "--source-commit", $SourceCommit,
        "--source-tree", $SourceTree
    )

    if ($Stage -eq "Construction") {
        if (Test-Path -LiteralPath $Root) { throw "Construction requires a new OutputRoot." }
        Invoke-JavaWithTimeout -ClassName $script:ConstructionClass -Arguments $Common `
            -Properties $BaseProperties -TimeoutSeconds 3600
        return
    }

    if ($Stage -eq "Correctness") {
        if (Test-Path -LiteralPath $Root) { throw "Correctness requires a new OutputRoot." }
        $GpuProperties = $BaseProperties + @(Get-CalibratedGpuProperties)
        Invoke-WithGpuLock -Body {
            Invoke-JavaWithTimeout -ClassName $script:CorrectnessClass `
                -Arguments $Common -Properties $GpuProperties `
                -TimeoutSeconds 600
        }
        return
    }

    $IsEvaluation = $Stage.StartsWith("Evaluation", [StringComparison]::Ordinal)
    $IsLeaf = $Stage.StartsWith("Leaf", [StringComparison]::Ordinal)
    $MainClass = if ($IsEvaluation) { $script:EvaluationClass } `
        elseif ($IsLeaf) { $script:LeafClass } else { $script:DepthClass }
    $Substage = if ($Stage -eq "DepthRender") { "render-assets" } `
        elseif ($Stage.EndsWith("Preflight")) { "preflight" } `
        elseif ($Stage.EndsWith("Measure")) { "measure-chunk" } else { "finalize" }
    $Arguments = @("--stage", $Substage) + $Common
    if ($Stage.EndsWith("Measure")) { $Arguments += @("--chunk-index", "$Index") }

    if ($Stage.EndsWith("Preflight") -and (Test-Path -LiteralPath $Root)) {
        throw "$Stage requires a new OutputRoot."
    }
    if (-not $Stage.EndsWith("Preflight") -and
            -not (Test-Path -LiteralPath $Root -PathType Container)) {
        throw "$Stage requires an existing preflight OutputRoot."
    }
    if (-not $IsMeasurement) {
        Invoke-Java -ClassName $MainClass -Arguments $Arguments -Properties $BaseProperties
        return
    }

    $GpuProperties = $BaseProperties + @(Get-CalibratedGpuProperties)
    $TimeoutSeconds = if ($Stage -eq "DepthRender") { 1800 } else { 600 }
    Invoke-WithGpuLock -Body {
        Invoke-JavaWithTimeout -ClassName $MainClass -Arguments $Arguments `
            -Properties $GpuProperties -TimeoutSeconds $TimeoutSeconds
    }
} finally {
    Pop-Location
}
