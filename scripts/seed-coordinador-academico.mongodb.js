/**
 * Inserta usuarios por defecto en MongoDB LOCAL (contraseña plana: 12345).
 * Hash BCrypt generado con org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder (mismo que la app).
 *
 * Uso (mongosh), desde la raíz del proyecto:
 *   mongosh "mongodb://127.0.0.1:27017/sit_titulacion" scripts/seed-coordinador-academico.mongodb.js
 *
 * O desde mongosh ya conectado a localhost:
 *   use sit_titulacion
 *   load("C:/ruta/a/sit/scripts/seed-coordinador-academico.mongodb.js")
 *
 * Nota: al arrancar el backend, SeedCoordinadorRunner y SeedAcademicoRunner
 * crean los mismos usuarios si no existen; este script sirve si solo quieres poblar Mongo sin levantar Spring.
 *
 * No sobrescribe contraseña si el usuario ya existe (solo inserta con $setOnInsert).
 */

use("sit_titulacion");

const PASSWORD_HASH_12345 =
  "$2a$10$7z6V4xL8APwnJ.KuG5BFCuJxB1pQSjel9sDAXS7bczm7kpfX7h6ly";

function upsertUsuario(doc) {
  const now = new Date();
  const res = db.usuarios.updateOne(
    { username: doc.username },
    {
      $setOnInsert: {
        username: doc.username,
        passwordHash: doc.passwordHash,
        rol: doc.rol,
        egresadoId: null,
        activo: true,
        fechaCreacion: now,
        fechaActualizacion: now,
      },
    },
    { upsert: true }
  );
  print(
    `${doc.username}: matched=${res.matchedCount} modified=${res.modifiedCount} upsertedId=${res.upsertedId}`
  );
}

upsertUsuario({
  username: "coordinador",
  passwordHash: PASSWORD_HASH_12345,
  rol: "coordinador",
});
upsertUsuario({
  username: "academico",
  passwordHash: PASSWORD_HASH_12345,
  rol: "academico",
});

print("Listo. Login: coordinador / 12345 y academico / 12345");
