import { Injectable } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, catchError, map, of, tap, throwError } from 'rxjs';
import { environment } from '../../environments/environment';

export const SIT_ACCESS_TOKEN_KEY = 'sit_access_token';

/**
 * Roles reales del sistema (ver Rol.kt en backend). Un solo rol por usuario.
 * Consolidación sep-2026: 'coordinador' se renombró a 'administrador' (mismo
 * acceso pleno), y el viejo 'administrador' (restringido) + 'division_estudios_prof_admin'
 * se fusionaron en 'dep' (Inicio del proceso + Seguimiento del proceso).
 */
export type RolUsuario =
  | 'administrador'
  | 'dep'
  | 'academico'
  | 'egresado'
  | 'apoyo_titulacion'
  | 'servicios_escolares'
  | 'subdireccion_academica';

export interface UsuarioActual {
  username: string;
  rol: RolUsuario;
  nombre?: string;
  segmento_academico?: string;
  carreras_asignadas?: string[];
}

interface LoginResponse extends UsuarioActual {
  access_token: string;
}

const AUTH = `${environment.apiUrl}/api/auth`;
const API = `${environment.apiUrl}/api`;

export interface CrearUsuarioBody {
  nombre: string;
  rol: string;
  correo_electronico: string;
  curp?: string;
  segmento_academico?: string;
  carreras_asignadas?: string[];
}

export interface UsuarioStaffItem {
  id: string;
  nombre: string;
  username: string;
  rol: RolUsuario;
  curp?: string;
  correo_electronico?: string;
  segmento_academico?: string;
  carreras_asignadas?: string[];
  activo: boolean;
}

@Injectable({ providedIn: 'root' })
export class AuthService {
  private usuario: UsuarioActual | null = null;

  constructor(private http: HttpClient) {}

  login(username: string, password: string): Observable<UsuarioActual> {
    return this.http
      .post<LoginResponse>(AUTH + '/login', { username: username.trim(), password }, {
        headers: { 'Content-Type': 'application/json' },
      })
      .pipe(
        tap((res) => {
          sessionStorage.setItem(SIT_ACCESS_TOKEN_KEY, res.access_token);
          this.usuario = {
            username: res.username,
            rol: res.rol,
            nombre: res.nombre,
            segmento_academico: res.segmento_academico,
          };
        }),
        map((res) => ({
          username: res.username,
          rol: res.rol,
          nombre: res.nombre,
          segmento_academico: res.segmento_academico,
        })),
      );
  }

  me(): Observable<UsuarioActual> {
    const token = sessionStorage.getItem(SIT_ACCESS_TOKEN_KEY);
    if (!token) {
      return throwError(
        () => new HttpErrorResponse({ status: 401, statusText: 'Sin sesión en esta pestaña' }),
      );
    }
    return this.http.get<UsuarioActual>(AUTH + '/me').pipe(tap((u) => (this.usuario = u)));
  }

  recuperarPassword(numeroControl: string): Observable<{ ok: boolean; message: string }> {
    return this.http.post<{ ok: boolean; message: string }>(
      `${AUTH}/recuperar-password`,
      { numeroControl: numeroControl.trim() },
    );
  }

  logout(): Observable<unknown> {
    return this.http.post(AUTH + '/logout', {}).pipe(
      tap(() => {
        this.usuario = null;
        sessionStorage.removeItem(SIT_ACCESS_TOKEN_KEY);
      }),
      catchError(() => {
        this.usuario = null;
        sessionStorage.removeItem(SIT_ACCESS_TOKEN_KEY);
        return of(null);
      }),
    );
  }

  getUsuario(): UsuarioActual | null {
    return this.usuario;
  }

  /** Limpia token y usuario en memoria (sesión expirada, 401, inactividad). */
  clearLocalSession(): void {
    this.usuario = null;
    sessionStorage.removeItem(SIT_ACCESS_TOKEN_KEY);
  }

  // Antes, cada método partía el string por si el rol venía "compuesto" (ej.
  // "Coordinador - Administrador"), con una lógica duplicada 6 veces y encima
  // inconsistente entre métodos. El backend ya no soporta roles compuestos
  // (un solo Rol por usuario, ver Rol.kt) — la comparación es directa.

  /** Acceso pleno (antes 'coordinador'). Incluye apoyo_titulacion, variante recortada del mismo perfil. */
  isAdministrador(): boolean {
    return this.usuario?.rol === 'administrador' || this.isApoyoTitulacion();
  }

  /** DEP (División de Estudios Profesionales): solo Inicio del proceso y Seguimiento del proceso. */
  isDep(): boolean {
    return this.usuario?.rol === 'dep';
  }

  /** Acceso al área de coordinación (pleno o DEP). */
  puedeAccederCoordinacion(): boolean {
    return this.isAdministrador() || this.isDep();
  }

  /** División de Estudios Profesionales — Apoyo a Titulación (solo inicio y seguimiento). */
  isApoyoTitulacion(): boolean {
    return this.usuario?.rol === 'apoyo_titulacion';
  }

  /** Listar/crear usuarios staff: solo administrador (DEP ya no administra cuentas). */
  puedeAdministrarUsuariosStaff(): boolean {
    return this.usuario?.rol === 'administrador';
  }

  isSubdireccionAcademica(): boolean {
    return this.usuario?.rol === 'subdireccion_academica';
  }

  /** El repositorio ya es público (sin guard); esto solo controla si se muestra su link en algún menú de staff. */
  puedeAccederRepositorio(): boolean {
    return this.usuario?.rol === 'administrador' || this.usuario?.rol === 'subdireccion_academica';
  }

  isEgresado(): boolean {
    return this.usuario?.rol === 'egresado';
  }

  isAcademico(): boolean {
    return this.usuario?.rol === 'academico';
  }

  /** Revertir liberación/registro en bandeja departamento: solo administrador (no DEP, no académicos). */
  puedeRevertirPasoDepartamento(): boolean {
    return this.usuario?.rol === 'administrador';
  }

  isServiciosEscolares(): boolean {
    return this.usuario?.rol === 'servicios_escolares';
  }

  crearUsuario(body: CrearUsuarioBody): Observable<{
    ok: boolean;
    message?: string;
    error?: string;
    correo_enviado?: boolean;
    detalle_correo?: string;
  }> {
    return this.http.post<{
      ok: boolean;
      message?: string;
      error?: string;
      correo_enviado?: boolean;
      detalle_correo?: string;
    }>(API + '/usuarios', body);
  }

  listarUsuarios(): Observable<UsuarioStaffItem[]> {
    return this.http.get<UsuarioStaffItem[]>(API + '/usuarios');
  }
}
