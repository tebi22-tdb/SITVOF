import { Component } from '@angular/core';
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

@Component({
  selector: 'app-gestion-cuentas',
  standalone: true,
  imports: [CommonModule, FormsModule, HeaderComponent],
  template: `
    <div class="gc-wrap">
      <app-header [showNuevoButton]="false" [showAgregarUsuarioButton]="false" [showVolverInicioButton]="true" (volverInicioClick)="volver()"></app-header>
      <div class="gc-contenido">
        <h1 class="gc-titulo">Gestión de cuentas</h1>
        <p class="gc-subtitulo">Busca por número de control (egresados) o por correo de acceso (personal staff).</p>

        <div class="gc-buscar">
          <input
            class="gc-input"
            type="text"
            placeholder="Número de control o correo de acceso"
            [(ngModel)]="busqueda"
            (keyup.enter)="buscar()"
          />
          <button class="gc-btn gc-btn-buscar" (click)="buscar()" [disabled]="cargando">
            {{ cargando ? 'Buscando…' : 'Buscar' }}
          </button>
        </div>
        <p *ngIf="errorBusqueda" class="gc-error">{{ errorBusqueda }}</p>

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
          </div>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .gc-wrap { min-height: 100vh; background: #f3f6fa; }
    .gc-contenido { padding: 0.75rem 1.5rem 2rem; max-width: 720px; margin: 0 auto; }
    .gc-titulo { font-size: 1.8rem; font-weight: 700; color: #1f2937; margin: 0 0 0.3rem; text-align: center; }
    .gc-subtitulo { text-align: center; color: #6b7280; font-size: 0.88rem; margin: 0 0 1.1rem; }
    .gc-buscar { display: flex; gap: 0.6rem; margin-bottom: 0.5rem; }
    .gc-input { flex: 1; padding: 0.55rem 0.8rem; border: 1px solid #a8c3e4; border-radius: 8px; font-size: 0.95rem; }
    .gc-btn { padding: 0.55rem 1.1rem; border: none; border-radius: 8px; cursor: pointer; font-size: 0.92rem; font-weight: 600; transition: background 0.13s; }
    .gc-btn:disabled { opacity: 0.6; cursor: default; }
    .gc-btn-buscar { background: #1f4c8f; color: #fff; }
    .gc-btn-buscar:hover:not(:disabled) { background: #163a6e; }
    .gc-btn-accion { background: #2563eb; color: #fff; white-space: nowrap; }
    .gc-btn-accion:hover:not(:disabled) { background: #1d4ed8; }
    .gc-btn-reset { background: #d97706; }
    .gc-btn-reset:hover:not(:disabled) { background: #b45309; }
    .gc-card { background: #fff; border: 1px solid #d1dde8; border-radius: 14px; padding: 1.25rem 1.5rem; }
    .gc-info { display: grid; grid-template-columns: auto 1fr; gap: 0.3rem 1rem; margin-bottom: 1.25rem; }
    .gc-label { font-size: 0.82rem; color: #6b7280; font-weight: 600; align-self: center; }
    .gc-valor { font-size: 0.95rem; color: #1f2937; align-self: center; }
    .gc-badge { background: #e0e7ff; color: #3730a3; padding: 0.15rem 0.55rem; border-radius: 999px; font-size: 0.8rem; font-weight: 600; display: inline-block; }
    .gc-acciones { display: flex; flex-direction: column; gap: 1.25rem; }
    .gc-accion-bloque { border-top: 1px solid #e5e7eb; padding-top: 1rem; }
    .gc-accion-bloque h3 { margin: 0 0 0.6rem; font-size: 1rem; font-weight: 650; color: #1f2937; }
    .gc-fila { display: flex; gap: 0.6rem; align-items: center; }
    .gc-desc { margin: 0 0 0.6rem; font-size: 0.85rem; color: #6b7280; }
    .gc-aviso { color: #92400e; background: #fef3c7; border-radius: 6px; padding: 0.4rem 0.7rem; }
    .gc-ok { color: #16a34a; font-size: 0.88rem; margin-top: 0.4rem; }
    .gc-error { color: #dc2626; font-size: 0.88rem; margin-top: 0.4rem; }
    .gc-password-box { margin-top: 0.75rem; background: #f0fdf4; border: 1px solid #86efac; border-radius: 8px; padding: 0.75rem 1rem; display: flex; flex-direction: column; gap: 0.3rem; }
    .gc-password { font-size: 1.15rem; font-family: monospace; color: #15803d; letter-spacing: 0.05em; }
  `],
})
export class GestionCuentasComponent {
  busqueda = '';
  cargando = false;
  errorBusqueda = '';
  usuario: UsuarioCuentaInfo | null = null;
  nuevoCorreo = '';
  guardandoCorreo = false;
  mensajeCorreo: { ok: boolean; texto: string } | null = null;
  reseteando = false;
  passwordTemporal = '';
  errorReset = '';

  constructor(private http: HttpClient, private router: Router) {}

  volver(): void {
    this.router.navigate(['/home']);
  }

  buscar(): void {
    const term = this.busqueda.trim();
    if (!term) return;
    this.cargando = true;
    this.errorBusqueda = '';
    this.usuario = null;
    this.nuevoCorreo = '';
    this.mensajeCorreo = null;
    this.passwordTemporal = '';
    this.errorReset = '';

    this.http.get<UsuarioCuentaInfo>(`${API}/buscar/${encodeURIComponent(term)}`).subscribe({
      next: (res) => {
        this.cargando = false;
        this.usuario = res;
      },
      error: () => {
        this.cargando = false;
        this.errorBusqueda = 'No se encontró ningún usuario con ese número de control o correo.';
      },
    });
  }

  cambiarCorreo(): void {
    const username = this.usuario?.username;
    if (!username || !this.nuevoCorreo.trim()) return;
    this.guardandoCorreo = true;
    this.mensajeCorreo = null;
    this.http.put(`${API}/${encodeURIComponent(username)}/correo`, { nuevo_correo: this.nuevoCorreo.trim() }).subscribe({
      next: () => {
        this.guardandoCorreo = false;
        this.mensajeCorreo = { ok: true, texto: 'Correo actualizado correctamente.' };
        if (this.usuario) {
          this.usuario.correo = this.nuevoCorreo.trim();
          if (!this.usuario.es_egresado) this.usuario.username = this.nuevoCorreo.trim();
        }
        this.nuevoCorreo = '';
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
    this.http.post<any>(`${API}/${encodeURIComponent(username)}/resetear-contrasena`, {}).subscribe({
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
}
