#!/usr/bin/env bash
# Desactiva seeds de usuarios de prueba en producción y reinicia SITVO.
# Ejecutar en el VPS (usuario con sudo):
#   sudo bash deploy/scripts/desactivar-seeds-prod.sh
# O desde tu PC (usa SIT_DEPLOY_USER / SIT_DEPLOY_HOST, no root ni IP en el repo):
#   ssh "${SIT_DEPLOY_USER}@${SIT_DEPLOY_HOST}" 'sudo bash -s' < deploy/scripts/desactivar-seeds-prod.sh

set -euo pipefail

# En el VPS actual el EnvironmentFile de systemd es /opt/sit/.env
ENV_FILE="${SIT_ENV_FILE:-/opt/sit/.env}"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "ERROR: no existe $ENV_FILE"
  exit 1
fi

upsert() {
  local key="$1"
  local value="$2"
  if grep -qE "^${key}=" "$ENV_FILE"; then
    sed -i "s|^${key}=.*|${key}=${value}|" "$ENV_FILE"
  else
    printf '\n%s=%s\n' "$key" "$value" >> "$ENV_FILE"
  fi
}

upsert SIT_SEED_ENABLED false
upsert SIT_SEED_USERS_ENABLED false
upsert SIT_SEED_SYNC_ADMIN false

chmod 600 "$ENV_FILE"
echo "Actualizado $ENV_FILE:"
grep -E '^SIT_SEED_' "$ENV_FILE" || true

systemctl restart sit
systemctl is-active sit
echo "Listo: seeds desactivados y servicio sit reiniciado."
