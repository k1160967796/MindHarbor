param([switch]$NoBuild)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $projectRoot
$composeFiles = @('-f', 'docker-compose.yml', '-f', 'docker-compose.media.yml', '-f', 'docker-compose.local.yml')
$mcpEnabled = $false
if (Test-Path -LiteralPath '.env.resend.local') {
    $composeFiles = @('--env-file','.env.resend.local') + $composeFiles + @('-f','docker-compose.callback-app.yml')
    $mcpEnabled = [IO.File]::ReadAllText((Join-Path $projectRoot '.env.resend.local')) -match '(?m)^MCP_REMOTE_ENABLED=true\s*$'
    if ($mcpEnabled) { $composeFiles += @('-f','docker-compose.mcp.yml') }
}

# Keep Docker data volumes and the existing host MySQL instance intact.
docker info --format '{{.ServerVersion}}' 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) {
    $dockerExe = Join-Path $env:LOCALAPPDATA 'Programs\DockerDesktop\Docker Desktop.exe'
    if (!(Test-Path -LiteralPath $dockerExe)) { $dockerExe = 'C:\Program Files\Docker\Docker\Docker Desktop.exe' }
    Start-Process -FilePath $dockerExe -WindowStyle Hidden
    $dockerReady = $false
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        Start-Sleep -Seconds 2
        docker info --format '{{.ServerVersion}}' 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) { $dockerReady = $true; break }
    }
    if (!$dockerReady) { throw 'Docker did not start. Check its error dialog before retrying.' }
}

try { Invoke-RestMethod 'http://localhost:11434/api/tags' -TimeoutSec 5 | Out-Null }
catch {
    $ollamaExecutable = (Get-Command ollama).Source
    Start-Process -FilePath $ollamaExecutable -ArgumentList 'serve' -WindowStyle Hidden
    Start-Sleep -Seconds 3
}
$installedModels = (Invoke-RestMethod 'http://localhost:11434/api/tags').models.name
foreach ($model in @('qwen2.5:1.5b', 'qwen2.5vl:3b', 'bge-m3:latest')) {
    if ($model -notin $installedModels) {
        ollama pull $model
        if ($LASTEXITCODE -ne 0) { throw "Model download failed: $model" }
    }
}

if (!$NoBuild) {
    $bundledJdk = Join-Path $env:USERPROFILE '.jdks\ms-17.0.16'
    if (Test-Path -LiteralPath $bundledJdk) { $env:JAVA_HOME = $bundledJdk }
    mvn -q -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw 'Java build failed. JDK 17 or newer is required.' }
    $buildServices = @('app','document-parser')
    if ($mcpEnabled) {
        mvn.cmd -q -f services/mcp-tools/pom.xml -DskipTests package
        if ($LASTEXITCODE -ne 0) { throw 'MCP build failed.' }
        $buildServices += 'mcp-tools'
    }
    docker compose @composeFiles build @buildServices
    if ($LASTEXITCODE -ne 0) { throw 'Container build failed.' }
}
docker compose @composeFiles up -d
if ($LASTEXITCODE -ne 0) { throw 'Container startup failed.' }
$appReady = $false
for ($attempt = 0; $attempt -lt 120; $attempt++) {
    try {
        $health = Invoke-RestMethod 'http://localhost:8080/actuator/health' -TimeoutSec 3
        if ($health.status -eq 'UP') { $appReady = $true; break }
    } catch { }
    Start-Sleep -Seconds 3
}
if (!$appReady) { throw 'Application not ready. Check: docker logs --tail 80 mindharbor-agent' }
Write-Host 'MindHarbor: http://localhost:8080'
Write-Host 'Student: student / student123; Admin: admin / admin123'
if ($mcpEnabled) { Write-Host 'Mail: MCP -> Resend; Excel: data/mcp-tools/mindharbor-reports.xlsx' }
else { Write-Host 'Local test mailbox: http://localhost:8025' }
Write-Host 'The OCR models load on first upload; large documents take longer.'
Start-Process 'http://localhost:8080'
