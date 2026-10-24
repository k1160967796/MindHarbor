$ErrorActionPreference = 'Stop'
$benchRoot = Join-Path $PSScriptRoot '../data/benchmarks/full-flow-20261023'
$manifest = Get-Content -LiteralPath (Join-Path $benchRoot 'manifest.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$relativeExcel = if ($manifest.excelPath) { $manifest.excelPath } else { 'mcp/mindharbor-reports.xlsx' }
$workbookPath = Join-Path $benchRoot $relativeExcel
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($workbookPath)
try {
    function Read-EntryXml($entryName) {
        $entry = $archive.GetEntry($entryName)
        if ($null -eq $entry) { return $null }
        $reader = [IO.StreamReader]::new($entry.Open())
        try { return [xml]$reader.ReadToEnd() } finally { $reader.Dispose() }
    }
    $stringsXml = Read-EntryXml 'xl/sharedStrings.xml'
    $shared = @(); if ($stringsXml) { $shared = @($stringsXml.GetElementsByTagName('si') | ForEach-Object { $_.InnerText }) }
    $sheet = Read-EntryXml 'xl/worksheets/sheet1.xml'
    $reportIds = @()
    foreach ($row in $sheet.GetElementsByTagName('row')) {
        if ([int]$row.r -eq 1) { continue }
        $cell = @($row.GetElementsByTagName('c') | Where-Object { $_.r -match '^A[0-9]+$' })[0]
        if ($cell.t -eq 's') { $reportIds += $shared[[int]$cell.v] }
        elseif ($cell.t -eq 'inlineStr') { $reportIds += $cell.is.InnerText }
        else { $reportIds += [string]$cell.v }
    }
    $evidence = Get-Content -LiteralPath (Join-Path $benchRoot 'database-evidence.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    $expected = @($evidence.psychological_reports | Where-Object excel_status -eq 'SUCCESS' | ForEach-Object { [string]$_.id })
    $missing = @($expected | Where-Object { $_ -notin $reportIds })
    $duplicates = @($reportIds | Group-Object | Where-Object Count -gt 1 | ForEach-Object Name)
    $unexpected = @($reportIds | Where-Object { $_ -notin $expected })
    $verification = @{ checkedAt=[DateTime]::UtcNow.ToString('o'); workbookValid=$true; rows=$reportIds.Count; expectedRows=$expected.Count; missing=$missing; unexpected=$unexpected; duplicates=$duplicates; pass=($missing.Count -eq 0 -and $duplicates.Count -eq 0 -and $unexpected.Count -eq 0) }
    $verification | ConvertTo-Json -Depth 5 | Set-Content -Encoding utf8 -LiteralPath (Join-Path $benchRoot 'excel-verification.json')
    $verification | ConvertTo-Json -Depth 5
    if (-not $verification.pass) { throw 'Excel evidence mismatch; see excel-verification.json' }
} finally { $archive.Dispose() }
