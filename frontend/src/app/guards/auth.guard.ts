import { inject } from '@angular/core';
import { Router, CanActivateFn } from '@angular/router';
import { AuthService, SIT_ACCESS_TOKEN_KEY } from '../services/auth.service';
import { map, catchError } from 'rxjs/operators';
import { of } from 'rxjs';

function redirigirInicioPorRol(auth: AuthService, router: Router): void {
  if (auth.isServiciosEscolares()) {
    router.navigate(['/servicios-escolares']);
  } else if (auth.isAcademico()) {
    router.navigate(['/departamento-academico']);
  } else if (auth.isSubdireccionAcademica()) {
    router.navigate(['/repositorio']);
  } else if (auth.isCoordinador()) {
    router.navigate(['/home']);
  } else if (auth.isEgresado()) {
    router.navigate(['/seguimiento']);
  } else {
    router.navigate(['/login']);
  }
}

/** Gestión de cuentas staff: solo coordinador o división administrativa. */
export const staffAdminGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  return auth.me().pipe(
    map(() => {
      if (auth.puedeAdministrarUsuariosStaff()) return true;
      if (auth.isCoordinador()) {
        router.navigate(['/home']);
        return false;
      }
      redirigirInicioPorRol(auth, router);
      return false;
    }),
    catchError(() => {
      router.navigate(['/login']);
      return of(false);
    }),
  );
};

/** Si ya hay sesión válida, no mostrar login (redirige al inicio del rol). */
export const loginGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  if (!sessionStorage.getItem(SIT_ACCESS_TOKEN_KEY)) return true;
  return auth.me().pipe(
    map(() => {
      redirigirInicioPorRol(auth, router);
      return false;
    }),
    catchError(() => of(true)),
  );
};

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

export const serviciosEscolaresGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  return auth.me().pipe(
    map(() => {
      if (auth.isServiciosEscolares() || auth.isCoordinador()) return true;
      if (auth.isAcademico()) {
        router.navigate(['/departamento-academico']);
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
    }),
  );
};

export const academicoGuard: CanActivateFn = (route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  return auth.me().pipe(
    map(() => {
      if (auth.isAcademico()) return true;
      if (auth.isServiciosEscolares()) {
        router.navigate(['/servicios-escolares']);
        return false;
      }
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
      if (auth.isSubdireccionAcademica()) {
        router.navigate(['/repositorio']);
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

export const repositorioGuard: CanActivateFn = (route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  return auth.me().pipe(
    map(() => {
      if (auth.puedeAccederRepositorio()) return true;
      if (auth.isAcademico()) {
        router.navigate(['/departamento-academico']);
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

export const egresadoGuard: CanActivateFn = (route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  return auth.me().pipe(
    map(() => {
      if (auth.isEgresado()) return true;
      if (auth.isServiciosEscolares()) {
        router.navigate(['/servicios-escolares']);
        return false;
      }
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
