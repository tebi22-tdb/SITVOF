import { inject } from '@angular/core';
import { Router, CanActivateFn } from '@angular/router';
import { AuthService } from '../services/auth.service';
import { map, catchError } from 'rxjs/operators';
import { of } from 'rxjs';

export const authGuard: CanActivateFn = (route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  return auth.me().pipe(
    map((user) => {
      auth.getUsuario(); // ya se guarda en me()
      return true;
    }),
    catchError(() => {
      router.navigate(['/login']);
      return of(false);
    })
  );
};

export const academicoGuard: CanActivateFn = (route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  return auth.me().pipe(
    map(() => {
      if (auth.isAcademico()) return true;
      if (auth.isCoordinador()) {
        router.navigate(['/home']);
        return false;
      }
      if (auth.isEgresado()) {
        router.navigate(['/seguimiento']);
        return false;
      }
      router.navigate(['/login']);
      return false;
    }),
    catchError(() => {
      router.navigate(['/login']);
      return of(false);
    })
  );
};

export const coordinadorGuard: CanActivateFn = (route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  return auth.me().pipe(
    map((user) => {
      const rol = auth.getUsuario()?.rol?.toLowerCase();
      if (rol === 'servicios_escolares') {
        router.navigate(['/servicios-escolares']);
        return false;
      }
      if (auth.isAcademico()) {
        router.navigate(['/departamento-academico']);
        return false;
      }
      if (auth.isCoordinador()) return true;
      if (auth.isEgresado()) {
        router.navigate(['/seguimiento']);
        return false;
      }
      router.navigate(['/login']);
      return false;
    }),
    catchError(() => {
      router.navigate(['/login']);
      return of(false);
    })
  );
};

export const egresadoGuard: CanActivateFn = (route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  return auth.me().pipe(
    map(() => {
      if (auth.isEgresado()) return true;
      if (auth.isAcademico()) {
        router.navigate(['/departamento-academico']);
        return false;
      }
      if (auth.isCoordinador()) {
        router.navigate(['/home']);
        return false;
      }
      router.navigate(['/login']);
      return false;
    }),
    catchError(() => {
      router.navigate(['/login']);
      return of(false);
    })
  );
};
