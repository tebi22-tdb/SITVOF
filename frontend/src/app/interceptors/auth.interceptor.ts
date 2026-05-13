import { HttpInterceptorFn } from '@angular/common/http';
import { environment } from '../../environments/environment';
import { SIT_ACCESS_TOKEN_KEY } from '../services/auth.service';

function isSitApiRequest(url: string): boolean {
  if (environment.apiUrl) {
    return url.startsWith(environment.apiUrl);
  }
  if (url.startsWith('/api')) return true;
  if (typeof window !== 'undefined' && url.startsWith(`${window.location.origin}/api`)) return true;
  return false;
}

/** Añade Bearer por pestaña (sessionStorage); sin cookie compartida entre pestañas. */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  if (!isSitApiRequest(req.url)) {
    return next(req);
  }
  const token = sessionStorage.getItem(SIT_ACCESS_TOKEN_KEY);
  if (!token) {
    return next(req);
  }
  return next(
    req.clone({
      setHeaders: { Authorization: `Bearer ${token}` },
    }),
  );
};
