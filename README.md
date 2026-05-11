# SITVO - Sistema Integral de Titulación

Proyecto de **residencia profesional**. Backend en **Kotlin** (Spring Boot 4), frontend en **Angular 18**, base de datos **MongoDB**.

## Estructura del proyecto (árbol)

```
sit/
├── build.gradle.kts
├── README.md
│
├── src/main/
│   ├── kotlin/.../sit/
│   │   ├── SitApplication.kt
│   │   ├── config/          → MongoConfig, SecurityConfig, UsuarioDetailsService, UsuarioPrincipal, SeedCoordinadorRunner
│   │   ├── domain/          → Egresado, Usuario, DatosPersonales, DatosProyecto, Documentos, etc.
│   │   ├── repository/      → EgresadoRepository, UsuarioRepository
│   │   ├── service/         → EgresadoService, UsuarioService, EmailService
│   │   └── web/
│   │       ├── RootController.kt
│   │       └── api/         → AuthController, EgresadoController, GlobalExceptionHandler, dto/
│   └── resources/
│       └── application.properties
│
├── frontend/
│   ├── angular.json
│   ├── package.json
│   └── src/
│       ├── index.html
│       ├── main.ts
│       └── app/
│           ├── app.component.ts
│           ├── app.config.ts
│           ├── app.routes.ts
│           ├── core/        → datos.ts (constantes e interfaz formulario)
│           ├── guards/      → auth.guard.ts
│           ├── interceptors/→ credentials.interceptor.ts
│           ├── layout/      → header/
│           ├── pages/       → login/, home/ (nuevo-egresado/), seguimiento/
│           └── services/    → auth.service.ts, egresado.service.ts
│
└── docs/
    ├── ESTRUCTURA.md
    ├── DEPLOY.md
    ├── MONGODB-PERSISTENCIA.md
    └── USUARIO-COORDINADOR.md
```

Detalle completo en **[docs/ESTRUCTURA.md](docs/ESTRUCTURA.md)**.

## Cómo ejecutar (desarrollo local)

1. **Backend:** en la raíz del proyecto ejecutar `.\gradlew bootRun --args='--spring.profiles.active=dev'` (puerto 8081). El perfil `dev` hace que la cookie de sesión sea para `localhost` y así al refrescar la página no te mande al login.
2. **Frontend:** `cd frontend` → `npm install` → `npm start` (puerto 4200). Usa el proxy para que las peticiones a `/api` vayan al backend y la sesión persista.
3. Abrir en el navegador: **http://localhost:4200** (redirige al login).

## Despliegue

Para desplegar en el servidor (compilar, subir JAR y frontend, Nginx, systemd), sigue **[docs/DEPLOY.md](docs/DEPLOY.md)**.

## Documentación

| Documento | Descripción |
|-----------|-------------|
| [docs/ESTRUCTURA.md](docs/ESTRUCTURA.md) | Estructura del proyecto y capas |
| [docs/DEPLOY.md](docs/DEPLOY.md) | Despliegue paso a paso |
| [docs/MONGODB-PERSISTENCIA.md](docs/MONGODB-PERSISTENCIA.md) | Persistencia y respaldos MongoDB |
| [docs/USUARIO-COORDINADOR.md](docs/USUARIO-COORDINADOR.md) | Usuario coordinador por defecto y roles |

## Tecnologías

- **Backend:** Kotlin, Spring Boot 4, Spring Security, Spring Data MongoDB, Jackson.
- **Frontend:** Angular 18, TypeScript.
- **Base de datos:** MongoDB (base `sit_titulacion`, colecciones `egresados` y `usuarios`).
