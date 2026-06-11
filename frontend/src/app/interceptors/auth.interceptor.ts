import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { environment } from '../../environments/environment';
import { AuthService, SIT_ACCESS_TOKEN_KEY } from '../services/auth.service';

function isSitApiRequest(url: string): boolean {
  if (environment.apiUrl) {
    return url.startsWith(environment.apiUrl);
  }
  if (url.startsWith('/api')) return true;
  if (typeof window !== 'undefined' && url.startsWith(`${window.location.origin}/api`)) return true;
  return false;
}

function esRutaLogin(url: string): boolean {
  return url.includes('/api/auth/login') || url.includes('/api/auth/recuperar-password');
}

/** Añade Bearer por pestaña (sessionStorage); ante 401 limpia sesión y redirige al login. */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  let request = req;
  if (isSitApiRequest(req.url)) {
    const token = sessionStorage.getItem(SIT_ACCESS_TOKEN_KEY);
    if (token) {
      request = req.clone({
        setHeaders: { Authorization: `Bearer ${token}` },
      });
    }
  }

  return next(request).pipe(
    catchError((err: HttpErrorResponse) => {
      if (
        err.status === 401 &&
        isSitApiRequest(req.url) &&
        !esRutaLogin(req.url) &&
        sessionStorage.getItem(SIT_ACCESS_TOKEN_KEY)
      ) {
        const auth = inject(AuthService);
        const router = inject(Router);
        auth.clearLocalSession();
        router.navigate(['/login'], { queryParams: { motivo: 'sesion' } });
      }
      return throwError(() => err);
    }),
  );
};
