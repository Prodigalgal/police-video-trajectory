param(
    [Parameter(Mandatory = $true)]
    [string] $BundlePath
)

$ErrorActionPreference = 'Stop'
$bundle = [System.IO.Path]::GetFullPath($BundlePath)
if (-not (Test-Path -LiteralPath $bundle -PathType Container)) {
    throw "Bundle directory not found: $bundle"
}

$manifestPath = Join-Path $bundle 'manifest.json'
$checksumsPath = Join-Path $bundle 'SHA256SUMS.txt'
$archivePath = Join-Path $bundle 'images.tar'
foreach ($required in @($manifestPath, $checksumsPath, $archivePath)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "Required file missing: $required"
    }
}

$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
if ($manifest.schema_version -ne 'offline-app-images-v1' -or $manifest.platform -ne 'linux/arm64') {
    throw 'Unsupported image package schema or platform.'
}
if (@($manifest.images).Count -ne 2 -or @($manifest.images.name | Sort-Object -Unique).Count -ne 2) {
    throw 'Expected unique Core and Runtime images.'
}
if ((@($manifest.images.name | Sort-Object) -join ',') -ne 'core,runtime') {
    throw 'Unexpected application image set.'
}
if ($manifest.source_commit -notmatch '^[a-f0-9]{40}$' -or $manifest.application_version -notmatch '^[0-9]+\.[0-9]+\.[0-9]+$') {
    throw 'Invalid source commit or application version.'
}
$expectedTagPattern = '^\d{8}-v' + [regex]::Escape([string]$manifest.application_version) + '-ci-' + ([string]$manifest.source_commit).Substring(0, 12) + '$'
if ($manifest.image_tag -notmatch $expectedTagPattern) {
    throw 'Image tag does not match the package version and source commit.'
}
$expectedReferences = @()
foreach ($image in $manifest.images) {
    if ($image.registry_digest -notmatch '^sha256:[a-f0-9]{64}$' -or [string]::IsNullOrWhiteSpace($image.reference)) {
        throw "Invalid reference or registry digest for $($image.name)."
    }
    if (-not $image.reference.EndsWith(":" + $manifest.image_tag, [System.StringComparison]::Ordinal)) {
        throw "Image tag does not match the package tag for $($image.name)."
    }
    $expectedReferences += [string]$image.reference
}

$verified = 0
$rootPrefix = $bundle.TrimEnd('\') + '\'
foreach ($line in Get-Content -LiteralPath $checksumsPath | Where-Object { $_.Trim() }) {
    $parts = $line.Trim() -split '\s+', 2
    if ($parts.Count -ne 2 -or $parts[0] -notmatch '^[a-fA-F0-9]{64}$') {
        throw "Invalid checksum row: $line"
    }
    $relative = $parts[1].Replace('/', '\')
    if ([System.IO.Path]::IsPathRooted($relative) -or $relative -match '(^|\\)\.\.(\\|$)') {
        throw "Unsafe package path: $relative"
    }
    $path = [System.IO.Path]::GetFullPath((Join-Path $bundle $relative))
    if (-not $path.StartsWith($rootPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Package path escapes its root: $relative"
    }
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Checksum target missing: $relative"
    }
    $actual = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $parts[0].ToLowerInvariant()) {
        throw "SHA-256 mismatch: $relative"
    }
    $verified++
}

& tar.exe -tf $archivePath *> $null
if ($LASTEXITCODE -ne 0) {
    throw 'images.tar is not a readable tar archive.'
}
$archiveManifestRaw = & tar.exe -xOf $archivePath manifest.json 2>$null
if ($LASTEXITCODE -ne 0) {
    throw 'images.tar does not contain a readable Docker image manifest.'
}
$archiveManifest = ($archiveManifestRaw -join "`n") | ConvertFrom-Json
$archiveReferences = @($archiveManifest | ForEach-Object { $_.RepoTags } | Where-Object { $_ } | Sort-Object -Unique)
if ((@($archiveReferences | Sort-Object) -join ',') -ne (@($expectedReferences | Sort-Object) -join ',')) {
    throw 'Image references in images.tar do not match manifest.json.'
}

Write-Output "Verified $verified package files for $($manifest.application_version) / $($manifest.image_tag)."
