param(
    [string]$OutputDir = "out/classes"
)

$ProjectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$OutputPath = if ([System.IO.Path]::IsPathRooted($OutputDir)) {
    [System.IO.Path]::GetFullPath($OutputDir)
} else {
    [System.IO.Path]::GetFullPath((Join-Path $ProjectRoot $OutputDir))
}
$RootPrefix = $ProjectRoot.TrimEnd([System.IO.Path]::DirectorySeparatorChar) +
        [System.IO.Path]::DirectorySeparatorChar
if (-not $OutputPath.StartsWith($RootPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Output directory must be inside the repository."
}

if (Test-Path -LiteralPath $OutputPath) {
    Remove-Item -LiteralPath $OutputPath -Recurse -Force
}
New-Item -ItemType Directory -Path $OutputPath -Force | Out-Null

Push-Location $ProjectRoot
try {
    $Sources = Get-ChildItem -LiteralPath "src" -Recurse -Filter "*.java" |
            Where-Object { $_.Name -ne "module-info.java" } |
            Select-Object -ExpandProperty FullName
    javac -cp "lib/*" -d $OutputPath $Sources
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
} finally {
    Pop-Location
}
