# SITVO - Frontend (Angular)

Frontend del Sistema Integral de Titulación. Se comunica con el backend en Kotlin (Spring Boot).

## Desarrollo

1. Instalar dependencias:
   ```bash
   npm install
   ```

2. Iniciar el backend (en la raíz del proyecto):
   ```bash
   ./gradlew bootRun
   ```
   El backend quedará en http://localhost:8080.

3. Iniciar Angular (en esta carpeta `frontend`):
   ```bash
   npm start
   ```
   La app quedará en http://localhost:4200. El proxy enviará `/api`, `/login` y `/logout` al backend.

4. Iniciar sesión: usuario `admin`, contraseña `admin`.

## Producción (VPS)

- Ejecutar `npm run build`.
- Copiar el contenido de `dist/sit-frontend/browser` al backend (por ejemplo en `src/main/resources/static/`) o configurar el servidor para servir esos estáticos.
- En `src/environments/environment.prod.ts` dejar `apiUrl: ''` si la app y la API se sirven desde el mismo dominio.
