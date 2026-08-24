param(
    [Parameter(Mandatory = $true)]
    [string]$RunDir
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$ProjectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$ResolvedRunDir = if ([IO.Path]::IsPathRooted($RunDir)) {
    [IO.Path]::GetFullPath($RunDir)
} else {
    [IO.Path]::GetFullPath((Join-Path $ProjectRoot $RunDir))
}
if (-not (Test-Path -LiteralPath $ResolvedRunDir -PathType Container)) {
    throw "Calibration run directory does not exist: $ResolvedRunDir"
}

$Names = @(
    "sphere", "box", "affineSphere", "affineBox",
    "plane", "nodeAabb", "interiorTraversal"
)

function Median {
    param([double[]]$Values)
    $Sorted = @($Values | Sort-Object)
    if ($Sorted.Count -eq 0) {
        throw "Cannot take the median of an empty sequence."
    }
    $Middle = [int][Math]::Floor($Sorted.Count / 2)
    if (($Sorted.Count % 2) -eq 0) {
        return 0.5 * ($Sorted[$Middle - 1] + $Sorted[$Middle])
    }
    return $Sorted[$Middle]
}

function Assert-FinitePositive {
    param([double]$Value, [string]$Label)
    if ([double]::IsNaN($Value) -or [double]::IsInfinity($Value) -or
            $Value -le 0.0) {
        throw "$Label must be finite and positive."
    }
}

function Assert-Close {
    param([double]$Actual, [double]$Expected, [string]$Label)
    if ([double]::IsNaN($Actual) -or [double]::IsInfinity($Actual) -or
            [double]::IsNaN($Expected) -or [double]::IsInfinity($Expected)) {
        throw "$Label is not finite."
    }
    $Scale = [Math]::Max(
        1.0, [Math]::Max([Math]::Abs($Actual), [Math]::Abs($Expected)))
    if ([Math]::Abs($Actual - $Expected) -gt 5.0e-7 * $Scale) {
        throw "$Label differs from the value recomputed from paired observations."
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

function Count-Value {
    param([object[]]$Values, [string]$Expected)
    return @($Values | Where-Object { "$_" -ceq $Expected }).Count
}

$ManifestPath = Join-Path $ResolvedRunDir "run-manifest.json"
if (-not (Test-Path -LiteralPath $ManifestPath -PathType Leaf)) {
    throw "Missing run-manifest.json."
}
$ManifestRaw = [IO.File]::ReadAllText($ManifestPath, [Text.Encoding]::UTF8)
$Manifest = $ManifestRaw | ConvertFrom-Json
$ManifestSha256 = (
    Get-FileHash -Algorithm SHA256 -LiteralPath $ManifestPath
).Hash.ToLowerInvariant()
if ([int]$Manifest.schemaVersion -ne 1 -or
        "$($Manifest.sourceCommit)" -notmatch '^[0-9a-f]{40}$' -or
        "$($Manifest.sourceTree)" -notmatch '^[0-9a-f]{40}$' -or
        "$($Manifest.compiledClassesSha256)" -notmatch '^[0-9a-f]{64}$' -or
        "$($Manifest.protocolSha256)" -notmatch '^[0-9a-f]{64}$') {
    throw "The run manifest is incomplete or malformed."
}

$ProtocolPath = Join-Path $ProjectRoot "$($Manifest.protocolPath)"
$ActualProtocolSha256 = (
    Get-FileHash -Algorithm SHA256 -LiteralPath $ProtocolPath
).Hash.ToLowerInvariant()
if ($ActualProtocolSha256 -cne "$($Manifest.protocolSha256)") {
    throw "The run manifest is bound to a different protocol document."
}
$Protocol = (
    [IO.File]::ReadAllText($ProtocolPath, [Text.Encoding]::UTF8) |
        ConvertFrom-Json
)

$ResultPaths = @(
    "contexts.csv", "summary.csv", "report.md", "validation.json", "SHA256SUMS.txt" |
        ForEach-Object { Join-Path $ResolvedRunDir $_ }
)
foreach ($Path in $ResultPaths) {
    if (Test-Path -LiteralPath $Path) {
        throw "Refusing to overwrite calibration result: $Path"
    }
}

$ContextFiles = @(
    Get-ChildItem -LiteralPath $ResolvedRunDir -Filter "context-*.json" |
        Sort-Object Name
)
if ($ContextFiles.Count -ne 5) {
    throw "Expected exactly five context files; found $($ContextFiles.Count)."
}

$ContextWeights = [ordered]@{}
$OperationOrders = @()
$PtxHashes = @()
$SourceHashes = @()
$WorkloadHashes = @()

foreach ($File in $ContextFiles) {
    $Data = [IO.File]::ReadAllText(
        $File.FullName, [Text.Encoding]::UTF8) | ConvertFrom-Json
    $Index = [int]$Data.contextIndex
    if ($File.BaseName -cne ("context-{0:D2}" -f $Index) -or
            $Index -lt 1 -or $Index -gt 5) {
        throw "$($File.Name) has an invalid context index."
    }
    if ([int]$Data.schemaVersion -ne 1 -or
            "$($Data.runManifestSha256)" -cne $ManifestSha256) {
        throw "$($File.Name) is not bound to this run manifest."
    }
    if ("$($Data.gpu.name)" -cne "$($Manifest.gpu.name)" -or
            "$($Data.gpu.computeCapability)" -cne
                "$($Protocol.gpu.computeCapability)") {
        throw "$($File.Name) was measured on a different GPU."
    }

    $ExpectedValues = [ordered]@{
        blocks = 256
        threadsPerBlock = 256
        iterationsPerThread = 2048
        operationCopiesPerIteration = 8
        sublaunchesPerObservation = 32
        iterationsPerSublaunch = 64
        operationBodiesPerSublaunch = 33554432
        operationBodiesPerObservation = 1073741824
        globalWorkloadRecords = 4096
        warmupPairsPerOperation = 4
        measuredPairsPerOperation = 12
    }
    foreach ($Entry in $ExpectedValues.GetEnumerator()) {
        if ([long]$Data.($Entry.Key) -ne [long]$Entry.Value) {
            throw "$($File.Name) has an unexpected $($Entry.Key)."
        }
    }
    if (-not [bool]$Data.raysPreNormalized -or
            -not [bool]$Data.primitiveRecordsFromGlobalMemory -or
            -not [bool]$Data.localSphereControlPerOperation -or
            "$($Data.sublaunchOrder)" -cne "alternating-within-pair") {
        throw "$($File.Name) does not describe the required paired workload."
    }

    foreach ($HashField in @(
            "compiledPtxSha256", "compiledCudaSourceSha256", "workloadSha256")) {
        if ("$($Data.$HashField)" -notmatch '^[0-9a-f]{64}$') {
            throw "$($File.Name) has an invalid $HashField."
        }
    }
    $PtxHashes += "$($Data.compiledPtxSha256)"
    $SourceHashes += "$($Data.compiledCudaSourceSha256)"
    $WorkloadHashes += "$($Data.workloadSha256)"

    $Order = @($Data.operationOrder)
    $ExpectedOrderSet = @($Names | Sort-Object)
    $ActualOrderSet = @($Order | Sort-Object)
    if ($Order.Count -ne $Names.Count -or
            (Compare-Object $ExpectedOrderSet $ActualOrderSet).Count -ne 0) {
        throw "$($File.Name) does not contain each operation exactly once."
    }
    $OperationOrders += ($Order -join ",")

    $Profiles = @($Data.profiles)
    if ($Profiles.Count -ne 3 -or
            (@($Profiles | ForEach-Object { [int]$_.id } | Sort-Object) -join ",") -ne
                "0,1,2") {
        throw "$($File.Name) must contain ray profiles 0, 1, and 2."
    }

    $RatiosByName = [ordered]@{}
    foreach ($Name in $Names) {
        $RatiosByName[$Name] = @()
    }

    foreach ($Profile in $Profiles) {
        $ProfileId = [int]$Profile.id
        foreach ($Name in $Names) {
            $Cost = $Profile.primitiveCosts.$Name
            if ($null -eq $Cost) {
                throw "$($File.Name) profile $ProfileId is missing $Name."
            }
            $Pairs = @($Cost.pairedRuns)
            if ($Pairs.Count -ne 12 -or
                    (@($Pairs | ForEach-Object { [int]$_.repeat } |
                        Sort-Object) -join ",") -ne "0,1,2,3,4,5,6,7,8,9,10,11") {
                throw "$($File.Name) profile $ProfileId $Name has invalid repeat ids."
            }
            if ((Count-Value @($Pairs.outerOrder) "operation_sphere") -ne 6 -or
                    (Count-Value @($Pairs.outerOrder) "sphere_operation") -ne 6 -or
                    (Count-Value @($Pairs.operationPairOrder) "setup_operation") -ne 6 -or
                    (Count-Value @($Pairs.operationPairOrder) "operation_setup") -ne 6 -or
                    (Count-Value @($Pairs.spherePairOrder) "setup_operation") -ne 6 -or
                    (Count-Value @($Pairs.spherePairOrder) "operation_setup") -ne 6) {
                throw "$($File.Name) profile $ProfileId $Name is not order-balanced."
            }

            $OperationNets = @()
            $SphereNets = @()
            foreach ($Pair in $Pairs) {
                $OperationNet = [double]$Pair.netNsPerTest
                $SphereNet = [double]$Pair.sphereNetNsPerTest
                Assert-Close $OperationNet (
                    [double]$Pair.operationNsPerTest -
                    [double]$Pair.setupNsPerTest
                ) "$($File.Name) profile $ProfileId $Name operation subtraction"
                Assert-Close $SphereNet (
                    [double]$Pair.sphereOperationNsPerTest -
                    [double]$Pair.sphereSetupNsPerTest
                ) "$($File.Name) profile $ProfileId $Name sphere subtraction"
                $OperationNets += $OperationNet
                $SphereNets += $SphereNet
            }

            $OperationMedian = Median ([double[]]$OperationNets)
            $SphereMedian = Median ([double[]]$SphereNets)
            Assert-FinitePositive $OperationMedian (
                "$($File.Name) profile $ProfileId $Name operation median")
            Assert-FinitePositive $SphereMedian (
                "$($File.Name) profile $ProfileId $Name sphere median")
            $Ratio = if ($Name -ceq "sphere") {
                1.0
            } else {
                $OperationMedian / $SphereMedian
            }
            Assert-Close ([double]$Cost.medianNetNsPerTest) $OperationMedian (
                "$($File.Name) profile $ProfileId $Name median")
            Assert-Close ([double]$Cost.localSphereMedianNetNsPerTest) $SphereMedian (
                "$($File.Name) profile $ProfileId $Name sphere median")
            Assert-Close ([double]$Cost.relativeWeight) $Ratio (
                "$($File.Name) profile $ProfileId $Name ratio")
            if ($Name -ceq "sphere") {
                Assert-Close (
                    [double]$Profile.spherePairedNetMedianNsPerTest
                ) $SphereMedian (
                    "$($File.Name) profile $ProfileId sphere control")
            }
            $RatiosByName[$Name] += $Ratio
        }
    }

    $Weights = [ordered]@{}
    foreach ($Name in $Names) {
        $Weight = Median ([double[]]$RatiosByName[$Name])
        Assert-Close ([double]$Data.recommendedWeights.$Name) $Weight (
            "$($File.Name) $Name context weight")
        $Weights[$Name] = $Weight
    }
    $ContextWeights[$File.BaseName] = $Weights
}

$Indices = @($ContextFiles | ForEach-Object {
    [int]([IO.Path]::GetFileNameWithoutExtension($_.Name) -replace "context-", "")
} | Sort-Object)
if (($Indices -join ",") -ne "1,2,3,4,5") {
    throw "Context indices must be 1, 2, 3, 4, and 5."
}
if (@($OperationOrders | Sort-Object -Unique).Count -ne 5) {
    throw "The five contexts must use five distinct rotated operation orders."
}
foreach ($Hashes in @($PtxHashes, $SourceHashes, $WorkloadHashes)) {
    if (@($Hashes | Sort-Object -Unique).Count -ne 1) {
        throw "Calibration contexts used different compiled programs or workloads."
    }
}

$ContextRows = foreach ($File in $ContextFiles) {
    $Row = [ordered]@{ context = $File.BaseName }
    foreach ($Name in $Names) {
        $Row[$Name] = [double]$ContextWeights[$File.BaseName][$Name]
    }
    [PSCustomObject]$Row
}
$SummaryRows = foreach ($Name in $Names) {
    $Values = [double[]]@($ContextFiles | ForEach-Object {
        [double]$ContextWeights[$_.BaseName][$Name]
    })
    [PSCustomObject]@{
        operation = $Name
        medianRelativeWeight = Median $Values
        minimumRelativeWeight = ($Values | Measure-Object -Minimum).Minimum
        maximumRelativeWeight = ($Values | Measure-Object -Maximum).Maximum
        contextCount = $Values.Count
    }
}

Write-NewUtf8File -Path (Join-Path $ResolvedRunDir "contexts.csv") -Content (
    ($ContextRows | ConvertTo-Csv -NoTypeInformation) -join [Environment]::NewLine)
Write-NewUtf8File -Path (Join-Path $ResolvedRunDir "summary.csv") -Content (
    ($SummaryRows | ConvertTo-Csv -NoTypeInformation) -join [Environment]::NewLine)

$Report = @(
    "# Primitive-cost calibration",
    "",
    "Weights are normalized to the sphere-intersection cost. Each context weight",
    "is the median of three ray-profile ratios. The reported final weight is the",
    "median across five fresh JVM/CUDA contexts.",
    "",
    "Source commit: $($Manifest.sourceCommit)",
    "Compiled classes: $($Manifest.compiledClassesSha256)",
    "Protocol: $($Manifest.protocolSha256)",
    "GPU: $($Manifest.gpu.name), driver $($Manifest.gpu.driverVersion)",
    "",
    "| Operation | Median | Minimum | Maximum |",
    "| --- | ---: | ---: | ---: |"
)
foreach ($Row in $SummaryRows) {
    $Culture = [Globalization.CultureInfo]::InvariantCulture
    $Report += "| {0} | {1} | {2} | {3} |" -f @(
        $Row.operation,
        $Row.medianRelativeWeight.ToString('0.000000000', $Culture),
        $Row.minimumRelativeWeight.ToString('0.000000000', $Culture),
        $Row.maximumRelativeWeight.ToString('0.000000000', $Culture)
    )
}
Write-NewUtf8File -Path (Join-Path $ResolvedRunDir "report.md") -Content (
    $Report -join [Environment]::NewLine)

$Validation = [ordered]@{
    schemaVersion = 1
    status = "pass"
    runManifestSha256 = $ManifestSha256
    sourceCommit = "$($Manifest.sourceCommit)"
    sourceTree = "$($Manifest.sourceTree)"
    compiledClassesSha256 = "$($Manifest.compiledClassesSha256)"
    protocolSha256 = "$($Manifest.protocolSha256)"
    gpu = $Manifest.gpu
    contextCount = 5
    rayProfilesPerContext = 3
    measuredPairsPerOperation = 12
    operationOrdersDistinct = 5
    compiledPtxSha256 = "$($PtxHashes[0])"
    compiledCudaSourceSha256 = "$($SourceHashes[0])"
    workloadSha256 = "$($WorkloadHashes[0])"
}
Write-NewUtf8File -Path (Join-Path $ResolvedRunDir "validation.json") -Content (
    $Validation | ConvertTo-Json -Depth 5)

$LedgerFiles = @(
    Get-ChildItem -LiteralPath $ResolvedRunDir -File |
        Where-Object Name -ne "SHA256SUMS.txt" |
        Sort-Object Name
)
$Ledger = foreach ($File in $LedgerFiles) {
    $Hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $File.FullName).Hash.ToLowerInvariant()
    "$Hash  $($File.Name)"
}
Write-NewUtf8File -Path (Join-Path $ResolvedRunDir "SHA256SUMS.txt") -Content (
    $Ledger -join [Environment]::NewLine)

Write-Host "Primitive-cost calibration validation PASS"
