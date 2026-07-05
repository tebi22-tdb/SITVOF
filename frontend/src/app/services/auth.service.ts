import { Injectable } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Observable, catchError, map, of, tap, throwError } from 'rxjs';
import { environment } from '../../environments/environment';

export const SIT_ACCESS_TOKEN_KEY = 'sit_access_token';

export interface UsuarioActual {
  username: string;
  rol: string;
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
  rol: string;
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

  isCoordinador(): boolean {
    const raw = this.usuario?.rol?.toLowerCase().trim() ?? '';
    if (!raw) return false;
    const partes = raw.split(/\s*[-–—]\s*|,/g).map((p) => p.trim()).filter(Boolean);
    const candidatos = partes.length ? partes : [raw.replace(/\s+/g, ' ')];
    return candidatos.some(
      (r) =>
        r === 'coordinador' ||
        r === 'apoyo_titulacion' ||
        r === 'apoyo titulacion',
    );
  }

  /** División administrativa o rol administrador: solo gestión de cuentas, no coordinación ni departamentos. */
  esDivisionAdministrativa(): boolean {
    const raw = this.usuario?.rol?.toLowerCase().trim() ?? '';
    if (!raw) return false;
    const partes = raw.split(/\s*[-–—]\s*|,/g).map((p) => p.trim()).filter(Boolean);
    const candidatos = partes.length ? partes : [raw.replace(/\s+/g, ' ')];
    return candidatos.some((r) => r === 'division_estudios_prof_admin' || r === 'administrador');
  }

  /** Acceso al área de coordinación (pleno o solo administrativo). */
  puedeAccederCoordinacion(): boolean {
    return this.isCoordinador() || this.esDivisionAdministrativa();
  }

  /** División de Estudios Profesionales — Apoyo a Titulación (solo inicio y seguimiento). */
  isApoyoTitulacion(): boolean {
    const raw = this.usuario?.rol?.toLowerCase().trim() ?? '';
    if (!raw) return false;
    const partes = raw.split(/\s*[-–—]\s*|,/g).map((p) => p.trim()).filter(Boolean);
    const candidatos = partes.length ? partes : [raw.replace(/\s+/g, ' ')];
    return candidatos.some((r) => r === 'apoyo_titulacion' || r === 'apoyo titulacion');
  }

  /** Listar/crear usuarios staff: coordinador o división administrativa (no apoyo a titulación). */
  puedeAdministrarUsuariosStaff(): boolean {
    const raw = this.usuario?.rol?.toLowerCase().trim() ?? '';
    if (!raw) return false;
    const partes = raw.split(/\s*[-–—]\s*|,/g).map((p) => p.trim()).filter(Boolean);
    const candidatos = partes.length ? partes : [raw];
    return candidatos.some((r) => r === 'coordinador' || r === 'division_estudios_prof_admin');
  }

  isSubdireccionAcademica(): boolean {
    return this.usuario?.rol?.toLowerCase().trim() === 'subdireccion_academica';
  }

  /** Solo coordinador, administrador y subdireccion_academica pueden acceder al repositorio. */
  puedeAccederRepositorio(): boolean {
    const raw = this.usuario?.rol?.toLowerCase().trim() ?? '';
    if (!raw) return false;
    const partes = raw.split(/\s*[-–—]\s*|,/g).map((p) => p.trim()).filter(Boolean);
    const candidatos = partes.length ? partes : [raw];
    return candidatos.some(
      (r) => r === 'coordinador' || r === 'administrador' || r === 'subdireccion_academica',
    );
  }

  isEgresado(): boolean {
    return this.usuario?.rol?.toLowerCase() === 'egresado';
  }

  isAcademico(): boolean {
    return this.usuario?.rol?.toLowerCase() === 'academico';
  }

  /** Revertir liberación/registro en bandeja departamento: coordinador o administración, no académicos. */
  puedeRevertirPasoDepartamento(): boolean {
    const raw = this.usuario?.rol?.toLowerCase().trim() ?? '';
    if (!raw) return false;
    const partes = raw.split(/\s*[-–—]\s*|,/g).map((p) => p.trim()).filter(Boolean);
    const candidatos = partes.length ? partes : [raw.replace(/\s+/g, ' ')];
    return candidatos.some(
      (r) => r === 'coordinador' || r === 'administrador' || r === 'division_estudios_prof_admin',
    );
  }

  isServiciosEscolares(): boolean {
    return this.usuario?.rol?.toLowerCase() === 'servicios_escolares';
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
