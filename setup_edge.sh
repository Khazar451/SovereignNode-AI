#!/usr/bin/env bash
# =============================================================================
# setup_edge.sh — SovereignNode AI Edge Server Bootstrap Script
# Run as root: sudo bash setup_edge.sh
# =============================================================================

set -euo pipefail

# ── Colour helpers ────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
info()    { echo -e "${CYAN}[INFO]${NC}  $*"; }
success() { echo -e "${GREEN}[OK]${NC}    $*"; }
warn()    { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error()   { echo -e "${RED}[ERROR]${NC} $*" >&2; exit 1; }

# ── Privilege check ───────────────────────────────────────────────────────────
[[ $EUID -ne 0 ]] && error "This script must be run as root. Try: sudo bash $0"

# ── 1. OS Update ──────────────────────────────────────────────────────────────
info "Step 1/5 — Updating OS packages..."
apt-get update -qq
apt-get upgrade -y -qq
success "OS packages updated."

# ── 2. Write /etc/docker/daemon.json ─────────────────────────────────────────
info "Step 2/5 — Writing Docker daemon configuration..."
mkdir -p /etc/docker
cat > /etc/docker/daemon.json << 'EOF'
{
  "log-driver": "json-file",
  "log-opts": {
    "max-size": "50m",
    "max-file": "3"
  },
  "default-runtime": "nvidia",
  "runtimes": {
    "nvidia": {
      "path": "nvidia-container-runtime",
      "runtimeArgs": []
    }
  }
}
EOF
success "/etc/docker/daemon.json written."

# ── 3. Write the systemd unit file ───────────────────────────────────────────
info "Step 3/5 — Installing sovereignnode.service unit file..."
cat > /etc/systemd/system/sovereignnode.service << 'EOF'
[Unit]
Description=SovereignNode AI — Docker Compose Stack
Documentation=https://github.com/Khazar451/SovereigNode-AI
After=docker.service network-online.target
Requires=docker.service
Wants=network-online.target

[Service]
Type=oneshot
RemainAfterExit=yes
WorkingDirectory=/home/khazar/Desktop/SovereignNode AI

# Give Docker a moment if it just started
ExecStartPre=/bin/sleep 5

ExecStart=/usr/bin/docker compose up -d --remove-orphans
ExecStop=/usr/bin/docker compose down --timeout 30

Restart=on-failure
RestartSec=10s

# Prevent aggressive restart loops from hammering the system
StartLimitIntervalSec=120
StartLimitBurst=5

[Install]
WantedBy=multi-user.target
EOF
success "/etc/systemd/system/sovereignnode.service installed."

# ── 4. Restart Docker to apply daemon.json ───────────────────────────────────
info "Step 4/5 — Restarting Docker daemon to apply new configuration..."
systemctl restart docker
sleep 3
if systemctl is-active --quiet docker; then
    success "Docker daemon is running with updated config."
else
    error "Docker daemon failed to restart. Check: journalctl -xeu docker"
fi

# ── 5. Reload systemd, enable, and start the service ─────────────────────────
info "Step 5/5 — Enabling and starting sovereignnode.service..."
systemctl daemon-reload
systemctl enable sovereignnode.service
systemctl start sovereignnode.service

sleep 5  # Allow compose to settle before checking status

if systemctl is-active --quiet sovereignnode.service; then
    success "sovereignnode.service is active and running."
else
    warn "Service may still be starting or encountered an issue."
    warn "Run the log command below to investigate."
fi

# ── Final banner ─────────────────────────────────────────────────────────────
echo ""
echo -e "${GREEN}╔══════════════════════════════════════════════════════════════════╗${NC}"
echo -e "${GREEN}║        SovereignNode AI — Edge Deployment Complete               ║${NC}"
echo -e "${GREEN}╚══════════════════════════════════════════════════════════════════╝${NC}"
echo ""
echo -e "  ${CYAN}View live service logs:${NC}"
echo -e "    ${YELLOW}journalctl -u sovereignnode.service -f${NC}"
echo ""
echo -e "  ${CYAN}View all Docker container logs:${NC}"
echo -e "    ${YELLOW}docker compose -f '/home/khazar/Desktop/SovereignNode AI/docker-compose.yml' logs -f${NC}"
echo ""
echo -e "  ${CYAN}Check service status:${NC}"
echo -e "    ${YELLOW}systemctl status sovereignnode.service${NC}"
echo ""
