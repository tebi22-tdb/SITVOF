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
    router.navigate(['/home/reportes']);
  } else if (auth.isApoyoTitulacion()) {
    // Antes de isAdministrador(): isAdministrador() incluye a apoyo_titulacion, así
    // que esta rama debe ganar primero — su única pantalla es Revisiones.
    router.navigate(['/home/revisiones']);
  } else if (auth.isDep()) {
    router.navigate(['/home/seguimiento-proceso']);
  } else if (auth.isAdministrador()) {
    router.navigate(['/home']);
  } else if (auth.isEgresado()) {
    router.navigate(['/seguimiento']);
  } else {
    router.navigate(['/login']);
  }
}

/** Gestión de cuentas staff: solo administrador. */
export const staffAdminGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  return auth.me().pipe(
    map(() => {
      if (auth.puedeAdministrarUsuariosStaff()) return true;
      if (auth.isAdministrador()) {
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

/** Puerta base de todo lo bajo /home/**: deja pasar Administrador (pleno o apoyo_titulacion) y DEP. */
export const coordinadorGuard: CanActivateFn = (route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  return auth.me().pipe(
    map((user) => {
      if (auth.isServiciosEscolares()) {
        router.navigate(['/servicios-escolares']);
        return false;
      }
      if (auth.isSubdireccionAcademica()) {
        // Solo Reportes: otras rutas /home/** se redirigen ahí.
        router.navigate(['/home/reportes']);
        return false;
      }
      if (auth.isAcademico()) {
        router.navigate(['/departamento-academico']);
        return false;
      }
      if (auth.isAdministrador() || auth.isDep()) return true;
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

/**
 * Reportes y analítica: administrador pleno (no apoyo) y Subdirección académica.
 * Es la única pantalla de /home a la que entra Subdirección.
 */
export const reportesGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  return auth.me().pipe(
    map(() => {
      if (auth.isSubdireccionAcademica()) return true;
      if (auth.isAdministrador() && !auth.isApoyoTitulacion()) return true;
      if (auth.isApoyoTitulacion()) {
        router.navigate(['/home/revisiones']);
        return false;
      }
      if (auth.isDep()) {
        router.navigate(['/home/seguimiento-proceso']);
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

/**
 * Bloquea Apoyo a Titulación (solo revisiones) y DEP (solo alta + seguimiento-proceso)
 * para las pantallas de staff pleno: dashboard, catálogos, docentes, reportes,
 * departamento académico (menú) y config institucional.
 */
export const noApoyoTitulacionGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  return auth.me().pipe(
    map(() => {
      if (auth.isApoyoTitulacion()) {
        router.navigate(['/home/revisiones']);
        return false;
      }
      if (auth.isDep()) {
        router.navigate(['/home/seguimiento-proceso']);
        return false;
      }
      return true;
    }),
    catchError(() => {
      router.navigate(['/login']);
      return of(false);
    }),
  );
};

/** Bloquea solo DEP: usar en Revisiones, la pantalla de Apoyo a Titulación (DEP no entra ahí). */
export const noDepGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  return auth.me().pipe(
    map(() => {
      if (auth.isDep()) {
        router.navigate(['/home/seguimiento-proceso']);
        return false;
      }
      return true;
    }),
    catchError(() => {
      router.navigate(['/login']);
      return of(false);
    }),
  );
};

/** Bloquea solo Apoyo a Titulación: usar en Inicio del proceso y Seguimiento, las dos pantallas de DEP. */
export const noApoyoTitulacionPeroDepGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  return auth.me().pipe(
    map(() => {
      if (auth.isApoyoTitulacion()) {
        router.navigate(['/home/revisiones']);
        return false;
      }
      return true;
    }),
    catchError(() => {
      router.navigate(['/login']);
      return of(false);
    }),
  );
};

export const serviciosEscolaresGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  return auth.me().pipe(
    map(() => {
      if (auth.isServiciosEscolares()) return true;
      if (auth.isAdministrador() && !auth.isApoyoTitulacion()) return true;
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
      if (auth.isSubdireccionAcademica()) {
        router.navigate(['/home/reportes']);
        return false;
      }
      if (auth.isAdministrador()) {
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
      if (auth.isSubdireccionAcademica()) {
        router.navigate(['/home/reportes']);
        return false;
      }
      if (auth.isAdministrador()) {
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
