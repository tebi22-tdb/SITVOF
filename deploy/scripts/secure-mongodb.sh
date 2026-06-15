#!/usr/bin/env bash
# Ejecutar EN EL VPS como root (o con sudo):
#   chmod +x secure-mongodb.sh && sudo ./secure-mongodb.sh
#
# Qué hace:
#  1. MongoDB solo escucha en 127.0.0.1 (no accesible desde Internet)
#  2. Habilita autenticación
#  3. Crea usuario de aplicación sit_app para la BD sit_titulacion
#  4. Opcional: cierra puerto 27017 en UFW si está activo
#
# Antes de ejecutar, exporta contraseñas (no las guardes en el historial del shell):
#   export SIT_MONGO_APP_PASSWORD='contraseña_fuerte_para_la_app'
#   export SIT_MONGO_ADMIN_PASSWORD='contraseña_fuerte_admin_opcional'

set -euo pipefail

DB_NAME="sit_titulacion"
APP_USER="sit_app"
ADMIN_USER="sit_admin"
APP_PASSWORD="${SIT_MONGO_APP_PASSWORD:-}"
ADMIN_PASSWORD="${SIT_MONGO_ADMIN_PASSWORD:-}"

if [[ -z "$APP_PASSWORD" ]]; then
  echo "ERROR: define SIT_MONGO_APP_PASSWORD antes de ejecutar."
  echo "  export SIT_MONGO_APP_PASSWORD='tu_contraseña_segura'"
  exit 1
fi

if ! command -v mongosh &>/dev/null && ! command -v mongo &>/dev/null; then
  echo "ERROR: instala MongoDB (mongosh) en el servidor."
  exit 1
fi

MONGO_SHELL="mongosh"
command -v mongosh &>/dev/null || MONGO_SHELL="mongo"

CONF="/etc/mongod.conf"
if [[ ! -f "$CONF" ]]; then
  echo "ERROR: no se encontró $CONF"
  exit 1
fi

echo "==> Respaldo de $CONF"
cp "$CONF" "${CONF}.bak.$(date +%Y%m%d%H%M%S)"

echo "==> bindIp 127.0.0.1"
if grep -q '^[[:space:]]*bindIp:' "$CONF"; then
  sed -i 's/^[[:space:]]*bindIp:.*/  bindIp: 127.0.0.1/' "$CONF"
else
  sed -i '/^net:/a\  bindIp: 127.0.0.1' "$CONF"
fi

echo "==> Reiniciar MongoDB (sin auth aún, solo localhost)"
systemctl restart mongod
sleep 2

echo "==> Crear usuario de aplicación"
$MONGO_SHELL --quiet "$DB_NAME" --eval "
  const u = db.getUser('${APP_USER}');
  if (u) {
    db.updateUser('${APP_USER}', { pwd: '${APP_PASSWORD}', roles: [{ role: 'readWrite', db: '${DB_NAME}' }] });
    print('Usuario ${APP_USER} actualizado.');
  } else {
    db.createUser({
      user: '${APP_USER}',
      pwd: '${APP_PASSWORD}',
      roles: [{ role: 'readWrite', db: '${DB_NAME}' }]
    });
    print('Usuario ${APP_USER} creado.');
  }
"

if [[ -n "$ADMIN_PASSWORD" ]]; then
  echo "==> Crear usuario admin"
  $MONGO_SHELL --quiet admin --eval "
    const u = db.getUser('${ADMIN_USER}');
    if (u) {
      db.updateUser('${ADMIN_USER}', { pwd: '${ADMIN_PASSWORD}', roles: ['root'] });
    } else {
      db.createUser({ user: '${ADMIN_USER}', pwd: '${ADMIN_PASSWORD}', roles: ['root'] });
    }
    print('Usuario admin listo.');
  "
fi

echo "==> Habilitar authorization en $CONF"
if grep -q '^[[:space:]]*authorization:' "$CONF"; then
  sed -i 's/^[[:space:]]*authorization:.*/  authorization: enabled/' "$CONF"
else
  sed -i '/^security:/a\  authorization: enabled' "$CONF" 2>/dev/null || {
    echo -e "\nsecurity:\n  authorization: enabled" >> "$CONF"
  }
fi

echo "==> Reiniciar MongoDB con autenticación"
systemctl restart mongod
sleep 2

echo "==> Probar conexión autenticada"
$MONGO_SHELL --quiet "mongodb://${APP_USER}:${APP_PASSWORD}@127.0.0.1:27017/${DB_NAME}?authSource=${DB_NAME}" --eval "db.runCommand({ ping: 1 })"

if command -v ufw &>/dev/null && ufw status | grep -q 'Status: active'; then
  echo "==> UFW: denegar 27017 desde fuera (si estaba abierto)"
  ufw deny 27017/tcp || true
fi

ENC_PASS=$(python3 -c "import urllib.parse; print(urllib.parse.quote('''${APP_PASSWORD}''', safe=''))" 2>/dev/null || echo "$APP_PASSWORD")
URI="mongodb://${APP_USER}:${ENC_PASS}@127.0.0.1:27017/${DB_NAME}?authSource=${DB_NAME}"

echo ""
echo "=============================================="
echo "MongoDB asegurado (solo localhost + auth)."
echo ""
echo "Añade en /etc/sit/sit.env del servidor:"
echo "SIT_MONGODB_URI=${URI}"
echo ""
echo "Reinicia el backend:"
echo "  sudo systemctl restart sit"
echo "=============================================="
