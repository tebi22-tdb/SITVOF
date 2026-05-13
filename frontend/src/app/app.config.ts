import { ApplicationConfig, provideZoneChangeDetection } from '@angular/core';
import { provideRouter, withHashLocation } from '@angular/router';
import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import { routes } from './app.routes';
import { authInterceptor } from './interceptors/auth.interceptor';

export const appConfig: ApplicationConfig = {
  providers: [
    provideZoneChangeDetection({ eventCoalescing: true }),
    /** Hash (#/ruta): al refrescar el navegador solo pide / y siempre carga index.html; evita 404 y redirecciones raras si Nginx no tiene try_files. */
    provideRouter(routes, withHashLocation()),
    provideHttpClient(withFetch(), withInterceptors([authInterceptor])),
  ],
};
