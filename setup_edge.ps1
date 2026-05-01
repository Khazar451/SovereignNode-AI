<#
.SYNOPSIS
SovereignNode AI - Windows Edge Deployment Script

.DESCRIPTION
This script checks if Docker Desktop is running, starts the SovereignNode AI 
Docker Compose stack in the background, and prints instructions for the user.
#>

$ErrorActionPreference = "Stop"

Write-Host "=========================================================" -ForegroundColor Cyan
Write-Host " SovereignNode AI Edge Deployment (Windows WSL 2)" -ForegroundColor Cyan
Write-Host "=========================================================" -ForegroundColor Cyan
Write-Host ""

# 1. Check if Docker is running
Write-Host "[1/2] Checking Docker daemon status..."
try {
    $dockerInfo = docker info 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Docker is not responding."
    }
    Write-Host "      Docker is running.`n" -ForegroundColor Green
} catch {
    Write-Host "`n[ERROR] Docker daemon is not running or not accessible." -ForegroundColor Red
    Write-Host "Please start Docker Desktop. Ensure that the 'WSL 2 based engine' is enabled" -ForegroundColor Yellow
    Write-Host "in Docker Desktop Settings -> General.`n" -ForegroundColor Yellow
    Exit 1
}

# 2. Start the Docker Compose stack
Write-Host "[2/2] Starting SovereignNode AI microservices..."
try {
    docker compose up -d --build
    Write-Host "      Containers started successfully.`n" -ForegroundColor Green
} catch {
    Write-Host "`n[ERROR] Failed to start Docker Compose stack." -ForegroundColor Red
    Exit 1
}

# 3. Print Success Message and Instructions
Write-Host "=========================================================" -ForegroundColor Cyan
Write-Host " DEPLOYMENT SUCCESSFUL!" -ForegroundColor Green
Write-Host "=========================================================" -ForegroundColor Cyan
Write-Host "The edge AI and telemetry backends are now running in the background."
Write-Host ""
Write-Host "Next Steps:" -ForegroundColor Yellow
Write-Host "  1. Open the dashboard in your browser:"
Write-Host "     http://localhost:5173" -ForegroundColor White
Write-Host ""
Write-Host "  2. Start the telemetry simulator (in a new terminal):"
Write-Host "     cd iot-telemetry-service" -ForegroundColor White
Write-Host "     python simulate_telemetry.py" -ForegroundColor White
Write-Host ""
Write-Host "To view inference engine logs and monitor the AI diagnostics in real-time:"
Write-Host "  docker compose logs -f inference-engine" -ForegroundColor White
Write-Host "=========================================================" -ForegroundColor Cyan
