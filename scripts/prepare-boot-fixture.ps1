param(
    [Parameter(Mandatory = $true)][string]$Repo,
    [Parameter(Mandatory = $true)][string]$BootJar,
    [string]$Destination = (Join-Path $PSScriptRoot '..\target\real-repo'),
    [string[]]$SourceRoot = @('src/main/java'),
    [string[]]$AdditionalClasspath = @()
)
$ErrorActionPreference = 'Stop'
$repoPath = (Resolve-Path -LiteralPath $Repo).Path
$jarPath = (Resolve-Path -LiteralPath $BootJar).Path
$destinationPath = [IO.Path]::GetFullPath($Destination)
if (Test-Path -LiteralPath $destinationPath) { throw 'Destination already exists. Choose a fresh directory to avoid stale sources.' }
[IO.Directory]::CreateDirectory($destinationPath) | Out-Null
foreach ($root in $SourceRoot) {
    $rootPath = [IO.Path]::GetFullPath((Join-Path $repoPath $root))
    if (-not $rootPath.StartsWith($repoPath + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Source root must be within the repository' }
    if (-not (Test-Path -LiteralPath $rootPath -PathType Container)) { throw "Missing source root: $root" }
    foreach ($file in Get-ChildItem -LiteralPath $rootPath -Recurse -File -Filter '*.java') {
        $relative = [IO.Path]::GetRelativePath($repoPath, $file.FullName)
        $target = Join-Path $destinationPath $relative
        [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($target)) | Out-Null
        [IO.File]::Copy($file.FullName, $target)
    }
}
$dependencyDir = Join-Path $destinationPath 'dependencies'
[IO.Directory]::CreateDirectory($dependencyDir) | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($jarPath)
$classpath = [Collections.Generic.List[string]]::new()
try {
    $entries = @($archive.Entries | Where-Object { $_.FullName -match '^BOOT-INF/lib/[^/\\]+\.jar$' })
    $index = $archive.GetEntry('BOOT-INF/classpath.idx')
    if ($null -ne $index) {
        $reader = [IO.StreamReader]::new($index.Open())
        try { $indexText = $reader.ReadToEnd() } finally { $reader.Dispose() }
        $ordered = @([regex]::Matches($indexText, '(?m)^- "(BOOT-INF/lib/[^/\\]+\.jar)"\r?$') | ForEach-Object { $_.Groups[1].Value })
        if ($ordered.Count -ne $entries.Count) { throw 'Invalid/incomplete BOOT-INF/classpath.idx' }
        $entries = @($ordered | ForEach-Object { $archive.GetEntry($_) })
    }
    if ($entries.Count -eq 0) { throw 'No BOOT-INF/lib dependencies in this JAR' }
    foreach ($entry in $entries) {
        if ($null -eq $entry) { throw 'Classpath index references a missing JAR' }
        $target = Join-Path $dependencyDir $entry.Name
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target)
        $classpath.Add('dependencies/' + $entry.Name)
    }
} finally { $archive.Dispose() }
foreach ($entry in $AdditionalClasspath) { $classpath.Add((Resolve-Path -LiteralPath $entry).Path) }
$classpathPath = Join-Path $destinationPath 'classpath.txt'
[IO.File]::WriteAllLines($classpathPath, $classpath, [Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText((Join-Path $destinationPath 'fixture-origin.txt'),
    "Source repo: $repoPath`nSource roots: $($SourceRoot -join ', ')`nDependency artifact: $jarPath`nPrepared UTC: $([DateTime]::UtcNow.ToString('O'))`nOnly Java source and build dependencies are copied. Pass each selected root with --source-root when indexing.`n",
    [Text.UTF8Encoding]::new($false))
Write-Output "Fixture: $destinationPath"
Write-Output "Classpath: $classpathPath"
Write-Output "Entries: $($classpath.Count)"
