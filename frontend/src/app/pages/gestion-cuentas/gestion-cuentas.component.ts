import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { HeaderComponent } from '../../layout/header/header.component';
import { environment } from '../../../environments/environment';

const API = `${environment.apiUrl}/api/usuarios`;

interface UsuarioCuentaInfo {
  username: string;
  correo: string;
  nombre: string;
  rol: string;
  es_egresado: boolean;
}

interface UsuarioListaItem {
  id: string;
  username: string;
  nombre: string;
  rol: string;
  correo_electronico: string | null;
  activo: boolean;
}

@Component({
  selector: 'app-gestion-cuentas',
  standalone: true,
  imports: [CommonModule, FormsModule, HeaderComponent],
  template: `
    <div class="gc-wrap">
      <app-header [showNuevoButton]="false" [showAgregarUsuarioButton]="false" [showVolverInicioButton]="true" (volverInicioClick)="volver()"></app-header>
      <div class="gc-contenido">
        <h1 class="gc-titulo">Gestión de cuentas</h1>
        <p class="gc-subtitulo">
          Selecciona una cuenta de la lista o búscala por número de control / correo de acceso.
        </p>

        <div class="gc-buscar">
          <input
            class="gc-input"
            type="text"
            placeholder="Número de control o correo de acceso"
            [(ngModel)]="busqueda"
            (keyup.enter)="buscar()"
          />
          <button class="gc-btn gc-btn-buscar" (click)="buscar()" [disabled]="cargandoBusqueda">
            {{ cargandoBusqueda ? 'Buscando…' : 'Buscar' }}
          </button>
          <button class="gc-btn gc-btn-sec" type="button" (click)="limpiarBusqueda()" [disabled]="!busqueda && !usuario">
            Limpiar
          </button>
        </div>
        <p *ngIf="errorBusqueda" class="gc-error">{{ errorBusqueda }}</p>
        <p *ngIf="mensajeEliminar" [class]="mensajeEliminar.ok ? 'gc-ok' : 'gc-error'">{{ mensajeEliminar.texto }}</p>

        <div class="gc-layout">
          <aside class="gc-lista-panel">
            <div class="gc-lista-cabecera">
              <span>Cuentas ({{ listaFiltrada.length }})</span>
              <button class="gc-btn-link" type="button" (click)="cargarLista()" [disabled]="cargandoLista">
                {{ cargandoLista ? 'Cargando…' : 'Actualizar' }}
              </button>
            </div>
            <p *ngIf="errorLista" class="gc-error">{{ errorLista }}</p>
            <p *ngIf="!cargandoLista && !errorLista && listaFiltrada.length === 0" class="gc-lista-vacia">
              No hay cuentas para mostrar.
            </p>
            <div class="gc-lista">
              <button
                *ngFor="let u of listaFiltrada"
                type="button"
                class="gc-lista-item"
                [class.gc-lista-item-activo]="usuario?.username === u.username"
                (click)="seleccionarDeLista(u)"
              >
                <span class="gc-lista-user">{{ u.username }}</span>
                <span class="gc-lista-nombre">{{ u.nombre || '—' }}</span>
                <span class="gc-lista-rol">{{ u.rol }}</span>
              </button>
            </div>
          </aside>

          <section class="gc-detalle-panel">
            <div *ngIf="!usuario" class="gc-vacio">
              <p>Selecciona una cuenta de la lista o usa el buscador.</p>
            </div>

            <div *ngIf="usuario" class="gc-card">
              <div class="gc-info">
                <span class="gc-label">Usuario</span>
                <span class="gc-valor">{{ usuario.username }}</span>
                <span class="gc-label">Nombre</span>
                <span class="gc-valor">{{ usuario.nombre }}</span>
                <span class="gc-label">Rol</span>
                <span class="gc-valor gc-badge">{{ usuario.rol }}</span>
                <span class="gc-label">Correo actual</span>
                <span class="gc-valor">{{ usuario.correo || '(sin correo)' }}</span>
              </div>

              <div class="gc-acciones">
                <div class="gc-accion-bloque">
                  <h3>Cambiar correo</h3>
                  <p *ngIf="!usuario.es_egresado" class="gc-desc gc-aviso">
                    Este usuario es staff — cambiar el correo también cambia su nombre de acceso al sistema.
                  </p>
                  <div class="gc-fila">
                    <input class="gc-input" type="email" placeholder="Nuevo correo electrónico" [(ngModel)]="nuevoCorreo" />
                    <button class="gc-btn gc-btn-accion" (click)="cambiarCorreo()" [disabled]="guardandoCorreo">
                      {{ guardandoCorreo ? 'Guardando…' : 'Guardar' }}
                    </button>
                  </div>
                  <p *ngIf="mensajeCorreo" [class]="mensajeCorreo.ok ? 'gc-ok' : 'gc-error'">{{ mensajeCorreo.texto }}</p>
                </div>

                <div class="gc-accion-bloque">
                  <h3>Resetear contraseña</h3>
                  <p class="gc-desc">Genera una contraseña temporal. Entrégala en persona — no se puede recuperar después.</p>
                  <button class="gc-btn gc-btn-accion gc-btn-reset" (click)="resetearContrasena()" [disabled]="reseteando">
                    {{ reseteando ? 'Generando…' : 'Generar contraseña temporal' }}
                  </button>
                  <div *ngIf="passwordTemporal" class="gc-password-box">
                    <span class="gc-label">Contraseña temporal</span>
                    <code class="gc-password">{{ passwordTemporal }}</code>
                  </div>
                  <p *ngIf="errorReset" class="gc-error">{{ errorReset }}</p>
                </div>

                <div class="gc-accion-bloque gc-accion-peligro">
                  <h3>Eliminar cuenta</h3>
                  <p class="gc-desc">
                    Borra el acceso al sistema. Si es egresado, el expediente de titulación se conserva; solo se elimina el login.
                  </p>
                  <ng-container *ngIf="!confirmarEliminar; else confirmarEliminarTpl">
                    <button class="gc-btn gc-btn-eliminar" type="button" (click)="confirmarEliminar = true" [disabled]="eliminando">
                      Eliminar cuenta
                    </button>
                  </ng-container>
                  <ng-template #confirmarEliminarTpl>
                    <p class="gc-aviso-peligro">
                      ¿Eliminar la cuenta <strong>{{ usuario.username }}</strong>? Esta acción no se puede deshacer.
                    </p>
                    <div class="gc-fila">
                      <button class="gc-btn gc-btn-sec" type="button" (click)="confirmarEliminar = false" [disabled]="eliminando">
                        Cancelar
                      </button>
                      <button class="gc-btn gc-btn-eliminar" type="button" (click)="eliminarCuenta()" [disabled]="eliminando">
                        {{ eliminando ? 'Eliminando…' : 'Sí, eliminar' }}
                      </button>
                    </div>
                  </ng-template>
                </div>
              </div>
            </div>
          </section>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .gc-wrap { min-height: 100vh; background: #f3f6fa; }
    .gc-contenido { padding: 0.75rem 1.25rem 2rem; max-width: 1100px; margin: 0 auto; }
    .gc-titulo { font-size: 1.8rem; font-weight: 700; color: #1f2937; margin: 0 0 0.3rem; text-align: center; }
    .gc-subtitulo { text-align: center; color: #6b7280; font-size: 0.88rem; margin: 0 0 1.1rem; }
    .gc-buscar { display: flex; gap: 0.6rem; margin-bottom: 0.5rem; flex-wrap: wrap; }
    .gc-input { flex: 1; min-width: 180px; padding: 0.55rem 0.8rem; border: 1px solid #a8c3e4; border-radius: 8px; font-size: 0.95rem; }
    .gc-btn { padding: 0.55rem 1.1rem; border: none; border-radius: 8px; cursor: pointer; font-size: 0.92rem; font-weight: 600; transition: background 0.13s; }
    .gc-btn:disabled { opacity: 0.6; cursor: default; }
    .gc-btn-buscar { background: #1f4c8f; color: #fff; }
    .gc-btn-buscar:hover:not(:disabled) { background: #163a6e; }
    .gc-btn-accion { background: #2563eb; color: #fff; white-space: nowrap; }
    .gc-btn-accion:hover:not(:disabled) { background: #1d4ed8; }
    .gc-btn-reset { background: #d97706; }
    .gc-btn-reset:hover:not(:disabled) { background: #b45309; }
    .gc-btn-eliminar { background: #dc2626; color: #fff; }
    .gc-btn-eliminar:hover:not(:disabled) { background: #b91c1c; }
    .gc-btn-sec { background: #e2e8f0; color: #334155; }
    .gc-btn-sec:hover:not(:disabled) { background: #cbd5e1; }
    .gc-btn-link { background: none; border: none; color: #1f4c8f; font-size: 0.82rem; font-weight: 600; cursor: pointer; padding: 0; }
    .gc-btn-link:hover:not(:disabled) { text-decoration: underline; }
    .gc-accion-peligro h3 { color: #991b1b; }
    .gc-aviso-peligro { color: #991b1b; background: #fef2f2; border: 1px solid #fecaca; border-radius: 6px; padding: 0.5rem 0.75rem; margin: 0 0 0.65rem; font-size: 0.88rem; }
    .gc-layout { display: grid; grid-template-columns: minmax(260px, 340px) 1fr; gap: 1rem; align-items: start; margin-top: 0.75rem; }
    .gc-lista-panel { background: #fff; border: 1px solid #d1dde8; border-radius: 14px; overflow: hidden; max-height: 70vh; display: flex; flex-direction: column; }
    .gc-lista-cabecera { display: flex; justify-content: space-between; align-items: center; padding: 0.75rem 1rem; border-bottom: 1px solid #e5e7eb; font-weight: 650; color: #1e3a5f; font-size: 0.92rem; }
    .gc-lista { overflow-y: auto; flex: 1; }
    .gc-lista-item { width: 100%; text-align: left; border: none; border-bottom: 1px solid #f1f5f9; background: #fff; padding: 0.7rem 1rem; cursor: pointer; display: flex; flex-direction: column; gap: 0.15rem; }
    .gc-lista-item:hover { background: #f8fafc; }
    .gc-lista-item-activo { background: #eff6ff; border-left: 3px solid #1f4c8f; }
    .gc-lista-user { font-size: 0.85rem; font-weight: 650; color: #1e3a5f; word-break: break-all; }
    .gc-lista-nombre { font-size: 0.88rem; color: #334155; }
    .gc-lista-rol { font-size: 0.75rem; color: #64748b; }
    .gc-lista-vacia { padding: 1rem; color: #6b7280; font-size: 0.88rem; margin: 0; }
    .gc-detalle-panel { min-width: 0; }
    .gc-vacio { background: #fff; border: 1px dashed #cbd5e1; border-radius: 14px; padding: 2.5rem 1.5rem; text-align: center; color: #64748b; }
    .gc-card { background: #fff; border: 1px solid #d1dde8; border-radius: 14px; padding: 1.25rem 1.5rem; }
    .gc-info { display: grid; grid-template-columns: auto 1fr; gap: 0.3rem 1rem; margin-bottom: 1.25rem; }
    .gc-label { font-size: 0.82rem; color: #6b7280; font-weight: 600; align-self: center; }
    .gc-valor { font-size: 0.95rem; color: #1f2937; align-self: center; word-break: break-word; }
    .gc-badge { background: #e0e7ff; color: #3730a3; padding: 0.15rem 0.55rem; border-radius: 999px; font-size: 0.8rem; font-weight: 600; display: inline-block; }
    .gc-acciones { display: flex; flex-direction: column; gap: 1.25rem; }
    .gc-accion-bloque { border-top: 1px solid #e5e7eb; padding-top: 1rem; }
    .gc-accion-bloque h3 { margin: 0 0 0.6rem; font-size: 1rem; font-weight: 650; color: #1f2937; }
    .gc-fila { display: flex; gap: 0.6rem; align-items: center; flex-wrap: wrap; }
    .gc-desc { margin: 0 0 0.6rem; font-size: 0.85rem; color: #6b7280; }
    .gc-aviso { color: #92400e; background: #fef3c7; border-radius: 6px; padding: 0.4rem 0.7rem; }
    .gc-ok { color: #16a34a; font-size: 0.88rem; margin-top: 0.4rem; }
    .gc-error { color: #dc2626; font-size: 0.88rem; margin-top: 0.4rem; }
    .gc-password-box { margin-top: 0.75rem; background: #f0fdf4; border: 1px solid #86efac; border-radius: 8px; padding: 0.75rem 1rem; display: flex; flex-direction: column; gap: 0.3rem; }
    .gc-password { font-size: 1.15rem; font-family: monospace; color: #15803d; letter-spacing: 0.05em; }
    @media (max-width: 860px) {
      .gc-layout { grid-template-columns: 1fr; }
      .gc-lista-panel { max-height: 280px; }
    }
  `],
})
export class GestionCuentasComponent implements OnInit {
  busqueda = '';
  cargandoBusqueda = false;
  cargandoLista = false;
  errorBusqueda = '';
  errorLista = '';
  lista: UsuarioListaItem[] = [];
  usuario: UsuarioCuentaInfo | null = null;
  nuevoCorreo = '';
  guardandoCorreo = false;
  mensajeCorreo: { ok: boolean; texto: string } | null = null;
  reseteando = false;
  passwordTemporal = '';
  errorReset = '';
  confirmarEliminar = false;
  eliminando = false;
  mensajeEliminar: { ok: boolean; texto: string } | null = null;

  get listaFiltrada(): UsuarioListaItem[] {
    const term = this.busqueda.trim().toLowerCase();
    if (!term) return this.lista;
    return this.lista.filter((u) =>
      [u.username, u.nombre, u.rol, u.correo_electronico]
        .filter(Boolean)
        .some((v) => String(v).toLowerCase().includes(term)),
    );
  }

  constructor(private http: HttpClient, private router: Router) {}

  ngOnInit(): void {
    this.cargarLista();
  }

  volver(): void {
    this.router.navigate(['/home']);
  }

  cargarLista(): void {
    this.cargandoLista = true;
    this.errorLista = '';
    this.http.get<UsuarioListaItem[]>(API).subscribe({
      next: (lista) => {
        this.cargandoLista = false;
        this.lista = lista ?? [];
      },
      error: () => {
        this.cargandoLista = false;
        this.errorLista = 'No se pudo cargar el listado de cuentas.';
      },
    });
  }

  seleccionarDeLista(u: UsuarioListaItem): void {
    this.resetDetalleEstado();
    this.usuario = {
      username: u.username,
      correo: u.correo_electronico || '',
      nombre: u.nombre || u.username,
      rol: u.rol,
      es_egresado: false,
    };
    this.errorBusqueda = '';
    this.mensajeEliminar = null;
  }

  buscar(): void {
    const term = this.busqueda.trim();
    if (!term) return;
    this.cargandoBusqueda = true;
    this.errorBusqueda = '';
    this.resetDetalleEstado();
    this.mensajeEliminar = null;

    this.http.get<UsuarioCuentaInfo>(`${API}/buscar/${encodeURIComponent(term)}`).subscribe({
      next: (res) => {
        this.cargandoBusqueda = false;
        this.usuario = res;
      },
      error: () => {
        this.cargandoBusqueda = false;
        this.usuario = null;
        this.errorBusqueda = 'No se encontró ningún usuario con ese número de control o correo.';
      },
    });
  }

  limpiarBusqueda(): void {
    this.busqueda = '';
    this.errorBusqueda = '';
    this.usuario = null;
    this.resetDetalleEstado();
    this.mensajeEliminar = null;
  }

  cambiarCorreo(): void {
    const username = this.usuario?.username;
    if (!username || !this.nuevoCorreo.trim()) return;
    this.guardandoCorreo = true;
    this.mensajeCorreo = null;
    const nuevo = this.nuevoCorreo.trim();
    this.http.put(`${API}/${encodeURIComponent(username)}/correo`, { nuevo_correo: nuevo }).subscribe({
      next: () => {
        this.guardandoCorreo = false;
        this.mensajeCorreo = { ok: true, texto: 'Correo actualizado correctamente.' };
        if (this.usuario) {
          this.usuario.correo = nuevo;
          if (!this.usuario.es_egresado && this.usuario.rol.toLowerCase() !== 'egresado') {
            this.usuario.username = nuevo;
          }
        }
        this.nuevoCorreo = '';
        this.cargarLista();
      },
      error: (err) => {
        this.guardandoCorreo = false;
        this.mensajeCorreo = { ok: false, texto: err?.error?.error || 'No se pudo actualizar el correo.' };
      },
    });
  }

  resetearContrasena(): void {
    const username = this.usuario?.username;
    if (!username) return;
    this.reseteando = true;
    this.passwordTemporal = '';
    this.errorReset = '';
    this.http.post<{ password_temporal?: string }>(`${API}/${encodeURIComponent(username)}/resetear-contrasena`, {}).subscribe({
      next: (res) => {
        this.reseteando = false;
        this.passwordTemporal = res.password_temporal || '';
      },
      error: (err) => {
        this.reseteando = false;
        this.errorReset = err?.error?.error || 'No se pudo resetear la contraseña.';
      },
    });
  }

  eliminarCuenta(): void {
    const username = this.usuario?.username;
    if (!username) return;
    this.eliminando = true;
    this.mensajeEliminar = null;
    this.http.delete<{ ok?: boolean; message?: string; error?: string }>(
      `${API}/${encodeURIComponent(username)}`,
    ).subscribe({
      next: (res) => {
        this.eliminando = false;
        this.confirmarEliminar = false;
        this.usuario = null;
        this.mensajeEliminar = { ok: true, texto: res?.message || 'Cuenta eliminada.' };
        this.errorBusqueda = '';
        this.cargarLista();
      },
      error: (err) => {
        this.eliminando = false;
        this.mensajeEliminar = {
          ok: false,
          texto: err?.error?.error || 'No se pudo eliminar la cuenta.',
        };
      },
    });
  }

  private resetDetalleEstado(): void {
    this.nuevoCorreo = '';
    this.mensajeCorreo = null;
    this.passwordTemporal = '';
    this.errorReset = '';
    this.confirmarEliminar = false;
  }
}
