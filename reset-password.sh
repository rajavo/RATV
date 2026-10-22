#!/usr/bin/env bash
# Gives the control panel a new password:
#   sudo bash /opt/ratv-server/reset-password.sh
set -euo pipefail
cd "$(dirname "$0")"
NEW="$(LC_ALL=C tr -dc 'A-HJ-NP-Za-km-z2-9' </dev/urandom 2>/dev/null | head -c 10 || true)"
mkdir -p data
printf '%s' "$NEW" >data/reset-password.txt
chmod 600 data/reset-password.txt
docker compose restart ratv >/dev/null
echo "New control panel password: $NEW"
