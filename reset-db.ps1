<#
.SYNOPSIS
    Resets the Warehouse Control Tower database to its seeded state.

.DESCRIPTION
    Drops and recreates the database, then starts the application so Flyway
    replays all migrations from V1. Useful before a demo or after the
    telemetry simulator has drained the stock.

    The simulator writes continuously, so a dashboard left running for a
    while drifts away from the seeded numbers. Run this to get a clean slate.

.PARAMETER Port
    HTTP port for the application. Default 8090 (8080 is usually taken on the
    factory floor by the SCADA/HMI runtime).

.PARAMETER DbName
    Database to reset. Default almoxarifado_db.

.PARAMETER NoStart
    Recreate the database but do not start the application.

.PARAMETER Yes
    Skip the confirmation prompt. Required for unattended runs.

.EXAMPLE
    .\reset-db.ps1

.EXAMPLE
    .\reset-db.ps1 -Port 8099 -Yes
#>
[CmdletBinding()]
param(
    [int]$Port = 8090,
    [string]$DbName = 'almoxarifado_db',
    [switch]$NoStart,
    [switch]$Yes
)

$ErrorActionPreference = 'Stop'

# psql reads the password from PGPASSWORD, not from our DB_PASSWORD. Without
# this mapping psql prompts on stdin and the script hangs forever waiting for
# input that never arrives.
if ($env:DB_PASSWORD) {
    $env:PGPASSWORD = $env:DB_PASSWORD
}

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$logFile     = Join-Path $projectRoot 'reset-db.log'

function Write-Step($message) {
    Write-Host "==> $message" -ForegroundColor Cyan
}

function Fail($message) {
    Write-Host "ERRO: $message" -ForegroundColor Red
    exit 1
}

# ---------------------------------------------------------------------
# 1. Preconditions
# ---------------------------------------------------------------------
Write-Step 'Verificando pre-requisitos'

if (-not $env:DB_USER)     { $env:DB_USER     = 'postgres' }
if (-not $env:DB_PASSWORD) {
    Fail 'Defina a senha do PostgreSQL antes de rodar: $env:DB_PASSWORD = "sua-senha"'
}
$env:DB_NAME   = $DbName
$env:PGPASSWORD = $env:DB_PASSWORD

# Locate psql: on PATH first, then the standard Windows install paths.
# Normalise to a plain path string, because Get-Command yields an object with
# .Source while Get-ChildItem yields a FileInfo with .FullName.
$psqlPath = $null

$onPath = Get-Command psql -ErrorAction SilentlyContinue
if ($onPath) {
    $psqlPath = $onPath.Source
} else {
    $pgRoot = Join-Path ${env:ProgramFiles} 'PostgreSQL'
    if (Test-Path $pgRoot) {
        $found = Get-ChildItem -Path $pgRoot -Filter 'psql.exe' -Recurse -File -ErrorAction SilentlyContinue |
                 Select-Object -First 1
        if ($found) { $psqlPath = $found.FullName }
    }
}

if (-not $psqlPath) {
    Fail 'psql.exe nao encontrado. Instale o PostgreSQL ou adicione o bin ao PATH.'
}

Write-Host "    psql    : $psqlPath" -ForegroundColor DarkGray
Write-Host "    banco   : $DbName" -ForegroundColor DarkGray
Write-Host "    usuario : $env:DB_USER" -ForegroundColor DarkGray

# Confirm the server is actually reachable before touching anything.
# -w stops psql from prompting for a password: if PGPASSWORD is wrong we want a
# clear failure, not an invisible hang on stdin.
$probe = & $psqlPath -U $env:DB_USER -h localhost -t -A -w -c 'SELECT 1' 2>&1
if ($LASTEXITCODE -ne 0 -or $probe -notmatch '1') {
    Fail "Nao foi possivel conectar ao PostgreSQL. Verifique DB_PASSWORD. Detalhe: $probe"
}

# ---------------------------------------------------------------------
# 2. Confirmation
# ---------------------------------------------------------------------
$existing = & $psqlPath -U $env:DB_USER -h localhost -t -A -w `
    -c "SELECT count(*) FROM pg_database WHERE datname = '$DbName'" 2>&1

if ($existing -eq '1' -and -not $Yes) {
    $rows = & $psqlPath -U $env:DB_USER -h localhost -d $DbName -t -A -w -c `
        "SELECT (SELECT count(*) FROM tb_work_order) || ' ordens, ' || (SELECT count(*) FROM tb_stock_movement) || ' movimentacoes'" 2>&1
    if (-not $rows) { $rows = '(vazio ou ainda sem migrations)' }

    Write-Host ''
    Write-Host "O banco '$DbName' existe e contem: $rows" -ForegroundColor Yellow
    Write-Host 'APAGAR TUDO e recriar a partir do seed? (s/N)' -ForegroundColor Yellow
    $answer = Read-Host
    if ($answer -ne 's' -and $answer -ne 'S') {
        Write-Host 'Cancelado.' -ForegroundColor Yellow
        exit 0
    }
}

# ---------------------------------------------------------------------
# 3. Stop the application
# ---------------------------------------------------------------------
Write-Step 'Parando a aplicacao (para nao reconectar)'
Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -match 'warehouse-control-tower|almoxarifado-control-tower' } |
    ForEach-Object {
        Write-Host "    encerrando PID $($_.ProcessId)" -ForegroundColor DarkGray
        Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
    }
Start-Sleep -Seconds 3

# ---------------------------------------------------------------------
# 4. Drop and recreate
# ---------------------------------------------------------------------
Write-Step "Recriando o banco '$DbName'"
& $psqlPath -U $env:DB_USER -h localhost -p 5432 -w `
    -c "DROP DATABASE IF EXISTS $DbName WITH (FORCE)" 2>&1 | ForEach-Object { "    $_" }
if ($LASTEXITCODE -ne 0) { Fail 'Falha ao remover o banco.' }

& $psqlPath -U $env:DB_USER -h localhost -p 5432 -w `
    -c "CREATE DATABASE $DbName ENCODING 'UTF8'" 2>&1 | ForEach-Object { "    $_" }
if ($LASTEXITCODE -ne 0) { Fail 'Falha ao criar o banco.' }

if ($NoStart) {
    Write-Host ''
    Write-Host 'Banco recriado. Use -NoStart para nao iniciar a aplicacao.' -ForegroundColor Green
    exit 0
}

# ---------------------------------------------------------------------
# 5. Start the application so Flyway repopulates
# ---------------------------------------------------------------------
Write-Step "Iniciando a aplicacao na porta $Port"

# Resolve a JDK that can actually run this project (Java 21+).
# Deriving it from `java` on PATH does not work: on Windows that usually points
# at the Oracle javapath JRE shim, which has no bin\java.exe.
# Registry key names are unreliable for ordering ("22" vs "13.0.2"), so each
# candidate is probed with `java -version` and the newest wins.
$javaHome = $null

function Get-JavaMajor([string]$javaExe) {
    # `java -version` prints to stderr, which under
    # $ErrorActionPreference='Stop' would surface as a terminating error.
    # Relax the preference for this call only.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $raw = & $javaExe -version 2>&1 | Out-String
    } catch {
        $raw = ''
    } finally {
        $ErrorActionPreference = $previous
    }
    if ($raw -match 'version "(\d+)') { return [int]$Matches[1] }
    return 0
}

$candidates = @()

if ($env:JAVA_HOME) {
    $candidates += $env:JAVA_HOME
}

# Registry-installed JDKs
$candidates += Get-ChildItem 'HKLM:\SOFTWARE\JavaSoft\JDK' -ErrorAction SilentlyContinue |
    ForEach-Object { (Get-ItemProperty $_.PSPath -ErrorAction SilentlyContinue).JavaHome }

# Common install roots
foreach ($root in @("$env:ProgramFiles\Java", "${env:ProgramFiles}\Eclipse Adoptium",
                   "${env:ProgramFiles}\Microsoft", "${env:ProgramFiles}\Amazon Corretto")) {
    if (Test-Path $root) {
        $candidates += Get-ChildItem $root -Directory -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match '^(jdk|openjdk|java|corretto)' } |
            ForEach-Object { $_.FullName }
    }
}

$ranked = $candidates |
    Where-Object { $_ -and (Test-Path (Join-Path $_ 'bin\java.exe')) } |
    ForEach-Object { [pscustomobject]@{ Dir = $_; Major = Get-JavaMajor (Join-Path $_ 'bin\java.exe') } } |
    Sort-Object Major -Descending

# The jar is compiled for Java 21, so anything older cannot load it.
$javaHome = ($ranked | Where-Object { $_.Major -ge 21 } | Select-Object -First 1).Dir

if (-not $javaHome) {
    $anyJdk = ($ranked | Where-Object { $_.Major -gt 0 } | Select-Object -First 1)
    if ($anyJdk) {
        Fail "Encontrado apenas JDK $($anyJdk.Major) em $($anyJdk.Dir). Este projeto exige Java 21 ou superior."
    }
    Fail 'JDK nao encontrado. Defina JAVA_HOME, ex.: $env:JAVA_HOME = "C:\Program Files\Java\jdk-22"'
}

Write-Host "    jdk     : $javaHome" -ForegroundColor DarkGray

$jar = Join-Path $projectRoot 'target\warehouse-control-tower.jar'
if (-not (Test-Path $jar)) {
    Fail "Jar nao encontrado em $jar. Rode antes: .\mvnw.cmd package -DskipTests"
}

$env:SERVER_PORT = "$Port"

# The path contains spaces ("Dashboard Corporativo"), and Start-Process does not
# quote -ArgumentList entries for you: without explicit quotes java receives a
# truncated path and dies with "Unable to access jarfile".
Start-Process -FilePath (Join-Path $javaHome 'bin\java.exe') `
    -ArgumentList @('-jar', "`"$jar`"") `
    -WorkingDirectory $projectRoot `
    -RedirectStandardOutput $logFile `
    -RedirectStandardError "$logFile.err" `
    -NoNewWindow

# Wait for the port to answer rather than sleeping a fixed amount.
Write-Step 'Aguardando a aplicacao subir'
$ready = $false
for ($i = 0; $i -lt 60; $i++) {
    Start-Sleep -Seconds 1
    try {
        Invoke-WebRequest "http://localhost:$Port/api/v1/dashboard/kpis" `
            -UseBasicParsing -TimeoutSec 3 | Out-Null
        $ready = $true
        break
    } catch {
        # keep waiting
    }
}

if (-not $ready) {
    Write-Host ''
    Write-Host 'A aplicacao nao respondeu a tempo. Veja o log:' -ForegroundColor Red
    Get-Content $logFile -Tail 25 | ForEach-Object { "    $_" }
    exit 1
}

# ---------------------------------------------------------------------
# 6. Report the fresh state
# ---------------------------------------------------------------------
$kpis = Invoke-RestMethod "http://localhost:$Port/api/v1/dashboard/kpis"

Write-Host ''
Write-Host 'Banco zerado com sucesso.' -ForegroundColor Green
Write-Host ''
Write-Host "  Itens no catalogo  : $($kpis.totalSupplyItems)  (A=$($kpis.classAItems) B=$($kpis.classBItems) C=$($kpis.classCItems))"
Write-Host "  Valor em estoque   : R$ $($kpis.totalInventoryValue)"
Write-Host "  Ruptura / critico  : $($kpis.stockoutItems) / $($kpis.criticalItems)"
Write-Host "  Saude do estoque   : $($kpis.inventoryHealthIndex)/100"
Write-Host "  Giro de estoque    : $($kpis.inventoryTurnover)x"
Write-Host "  Tempo de atendimento: $($kpis.averageFulfilmentHours)h"
Write-Host ''
Write-Host "  Painel: http://localhost:$Port" -ForegroundColor Cyan
Write-Host ''
Write-Host '  Ctrl+C para encerrar.' -ForegroundColor DarkGray