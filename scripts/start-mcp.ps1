param([switch]$NoBuild)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $projectRoot
$localConfig = Join-Path $projectRoot '.env.resend.local'
if (!(Test-Path -LiteralPath $localConfig)) { throw 'Save Resend settings to .env.resend.local first.' }
$configText = [IO.File]::ReadAllText($localConfig)
if ($configText -notmatch '(?m)^MCP_SERVICE_TOKEN=') {
    $bytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    $serviceToken = [Convert]::ToBase64String($bytes)
    $configText = $configText.TrimEnd()+"`nMCP_SERVICE_TOKEN=$serviceToken`n"
}
$configText = [regex]::Replace($configText, '(?m)^MCP_REMOTE_ENABLED=.*\r?\n?', '')
$configText = $configText.TrimEnd()+"`nMCP_REMOTE_ENABLED=true`n"
[IO.File]::WriteAllText($localConfig,$configText,(New-Object Text.UTF8Encoding($false)))
$compose = @('--env-file','.env.resend.local','-f','docker-compose.yml','-f','docker-compose.media.yml',
    '-f','docker-compose.local.yml','-f','docker-compose.callback-app.yml','-f','docker-compose.mcp.yml')
if (!$NoBuild) {
    $jdk = Join-Path $env:USERPROFILE '.jdks\ms-17.0.16'
    if (Test-Path -LiteralPath $jdk) { $env:JAVA_HOME=$jdk; $env:PATH="$jdk\bin;$env:PATH" }
    mvn.cmd -q -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw 'Application build failed.' }
    mvn.cmd -q -f services/mcp-tools/pom.xml -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw 'MCP tools build failed.' }
    docker compose @compose build app mcp-tools
    if ($LASTEXITCODE -ne 0) { throw 'Docker build failed.' }
}
# New service owns a separate workbook; preserve existing archive on first migration.
$toolData=Join-Path $projectRoot 'data\mcp-tools'
New-Item -ItemType Directory -Force -Path $toolData | Out-Null
$oldBook=Join-Path $projectRoot 'data\mindbridge-reports.xlsx'
$newBook=Join-Path $toolData 'mindbridge-reports.xlsx'
if ((Test-Path -LiteralPath $oldBook) -and !(Test-Path -LiteralPath $newBook)) {
    Copy-Item -LiteralPath $oldBook -Destination $newBook
}
docker compose @compose up -d --no-deps --no-build mcp-tools
if ($LASTEXITCODE -ne 0) { throw 'MCP tools startup failed.' }
$toolsReady=$false
for ($attempt=0; $attempt -lt 20; $attempt++) {
    # Check the packaged MCP route, not just Spring's general health endpoint.
    $status = docker exec mindbridge-mcp-tools sh -c 'curl -s --max-time 2 -o /dev/null -w "%{http_code}" -H "Authorization: Bearer $MCP_SERVICE_TOKEN" -H "Accept: text/event-stream" http://localhost:8081/sse' 2>$null
    if (($status -join '').Trim() -eq '200') { $toolsReady=$true; break }
    Start-Sleep -Seconds 2
}
if (!$toolsReady) { throw 'Authenticated MCP endpoint did not become ready; application was not restarted.' }
docker compose @compose up -d --no-deps --no-build app
if ($LASTEXITCODE -ne 0) { throw 'Application startup failed.' }
Write-Output 'MCP transport enabled. Durable pending business tasks will be processed.'
Write-Output 'MindBridge: http://localhost:8080'
Write-Output 'Excel: data/mcp-tools/mindbridge-reports.xlsx'
