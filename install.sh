#!/usr/bin/env bash
# RATV Server: install or update on Ubuntu / Debian.
#
#   curl -fsSL https://raw.githubusercontent.com/rajavo/RATV/server/install.sh | sudo bash
#
# Run the same command again at any time to update. Channels, accounts and settings are kept.
set -euo pipefail

SOURCE_TGZ="${RATV_SOURCE:-https://codeload.github.com/rajavo/RATV/tar.gz/refs/heads/server}"
DIR="${RATV_DIR:-/opt/ratv-server}"

step() { printf '\n\033[1;33m==> %s\033[0m\n' "$*"; }
die() {
  printf '\n\033[1;31mERROR: %s\033[0m\n' "$*" >&2
  exit 1
}

[ "$(id -u)" -eq 0 ] || die "Run it as root (put sudo before bash)."
command -v apt-get >/dev/null 2>&1 || die "This installer supports Ubuntu and Debian only."

step "Installing the tools it needs"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq curl ca-certificates tar >/dev/null

if ! command -v docker >/dev/null 2>&1; then
  step "Installing Docker"
  curl -fsSL https://get.docker.com | sh
fi
systemctl enable --now docker >/dev/null 2>&1 || true
if ! docker compose version >/dev/null 2>&1; then
  apt-get install -y -qq docker-compose-plugin >/dev/null 2>&1 || die "Docker Compose is missing."
fi

step "Getting RATV Server"
mkdir -p "$DIR"
SELF="${BASH_SOURCE[0]:-}"
SELF_DIR=""
if [ -n "$SELF" ] && [ -f "$SELF" ]; then SELF_DIR="$(cd "$(dirname "$SELF")" && pwd)"; fi
if [ -n "$SELF_DIR" ] && [ -f "$SELF_DIR/server.js" ] && [ -f "$SELF_DIR/docker-compose.yml" ]; then
  # Run from an unpacked copy (for example the zip uploaded to the server).
  if [ "$SELF_DIR" != "$DIR" ]; then
    rm -rf "$DIR/lib" "$DIR/public"
    (cd "$SELF_DIR" && tar --exclude=./data --exclude=./.env -cf - .) | (cd "$DIR" && tar -xf -)
  fi
else
  tmp="$(mktemp -d)"
  curl -fsSL "$SOURCE_TGZ" | tar -xz -C "$tmp" --strip-components=1
  [ -f "$tmp/server.js" ] || die "The download did not work. Check the server's internet connection."
  rm -rf "$DIR/lib" "$DIR/public"
  (cd "$tmp" && tar --exclude=./data --exclude=./.env -cf - .) | (cd "$DIR" && tar -xf -)
  rm -rf "$tmp"
fi
cd "$DIR"
chmod +x install.sh reset-password.sh 2>/dev/null || true
mkdir -p data/epg

FIRST_INSTALL=0
[ -f data/db.json ] || FIRST_INSTALL=1

if [ ! -f .env ]; then
  PORT=80
  if ss -ltnH 2>/dev/null | awk '{print $4}' | grep -qE '(^|[:.])80$'; then PORT=8080; fi
  PASS="$(LC_ALL=C tr -dc 'A-HJ-NP-Za-km-z2-9' </dev/urandom 2>/dev/null | head -c 10 || true)"
  [ -n "$PASS" ] || PASS="ratv$(date +%s | tail -c 7)"
  printf 'PORT=%s\nTZ=Asia/Riyadh\nADMIN_PASSWORD=%s\n' "$PORT" "$PASS" >.env
  chmod 600 .env
fi
set -a
# shellcheck disable=SC1091
. ./.env
set +a
PORT="${PORT:-80}"

step "Starting the server (the first time takes a few minutes)"
docker compose up -d --build --remove-orphans ratv
for _ in $(seq 1 60); do
  curl -fsS "http://127.0.0.1:${PORT}/healthz" >/dev/null 2>&1 && break
  sleep 2
done
curl -fsS "http://127.0.0.1:${PORT}/healthz" >/dev/null 2>&1 || die "The server did not start. Details: docker compose -f $DIR/docker-compose.yml logs ratv"

step "Preparing the free channels and the programme guide"
for _ in $(seq 1 120); do
  [ -s data/epg/channels.xml ] && break
  sleep 2
done
docker compose pull -q epg >/dev/null 2>&1 || true
docker compose up -d epg || echo "(The programme guide collector did not start; the channels still work.)"

if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -q "Status: active"; then
  ufw allow "${PORT}/tcp" >/dev/null || true
fi

IP="$(curl -fsS --max-time 6 https://api.ipify.org 2>/dev/null || true)"
[ -n "$IP" ] || IP="$(hostname -I 2>/dev/null | awk '{print $1}')"
URL="http://${IP}"
[ "$PORT" = "80" ] || URL="${URL}:${PORT}"

USER_NAME=""
USER_PASS=""
if [ -f data/first-run.json ]; then
  USER_NAME="$(sed -n 's/.*"username": *"\([^"]*\)".*/\1/p' data/first-run.json | head -1)"
  USER_PASS="$(sed -n 's/.*"password": *"\([^"]*\)".*/\1/p' data/first-run.json | head -1)"
fi

printf '\n\033[1;32m==============================================\033[0m\n'
if [ "$FIRST_INSTALL" = "1" ]; then
  echo " RATV Server is ready"
  echo
  echo " Control panel : ${URL}/admin"
  echo " Panel password: ${ADMIN_PASSWORD}"
  if [ -n "$USER_NAME" ]; then
    echo
    echo " Sign in to RATV with:"
    echo "   Server   : ${URL}"
    echo "   Username : ${USER_NAME}"
    echo "   Password : ${USER_PASS}"
  fi
  echo
  echo " The free channels are checked over the next minutes;"
  echo " the programme guide fills in during the first hours."
else
  echo " RATV Server is updated"
  echo " Control panel: ${URL}/admin"
fi
printf '\033[1;32m==============================================\033[0m\n'
echo " If the panel does not open, allow TCP port ${PORT} in your"
echo " server provider's firewall (security group / security list)."
