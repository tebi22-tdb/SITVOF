import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { HeaderComponent } from '../../layout/header/header.component';
import {
  ConfigInstitucionalService,
  ConfigGlobalResponse,
  ConfigDepartamentoResponse,
} from '../../services/config-institucional.service';

@Component({
  selector: 'app-config-institucional',
  standalone: true,
  imports: [CommonModule, FormsModule, HeaderComponent],
  template: `
    <div class="page">
      <app-header [showNuevoButton]="false" [showAgregarUsuarioButton]="false"
        [showVolverInicioButton]="true" (volverInicioClick)="volver()"></app-header>
      <div class="contenido">
        <h1 class="titulo">Configuración Institucional</h1>
        <p class="subtitulo">Administra los datos que cambian anualmente en los formatos XXXII y XXXIII (hojas 32 y 33).</p>

        <!-- ── Sección Global ─────────────────────────────────────────────── -->
        <section class="seccion">
          <h2 class="sec-titulo">Datos globales</h2>
          <p class="sec-desc">Nombre y título del Jefe(a) de la División de Estudios Profesionales.</p>
          <div *ngIf="globalLoading" class="loading">Cargando...</div>
          <form *ngIf="!globalLoading && global" (ngSubmit)="guardarGlobal()" class="form-grid">
            <div class="campo">
              <label class="lbl">Nombre del Jefe(a) de División</label>
              <input class="inp inp-uc" type="text" [(ngModel)]="global!.jefeDivisionNombre" name="jefeDivisionNombre"
                placeholder="Ej. MANUEL FABIAN ROJAS"
                (blur)="global!.jefeDivisionNombre = global!.jefeDivisionNombre.toUpperCase()"/>
            </div>
            <div class="campo">
              <label class="lbl">Título (JEFE / JEFA)</label>
              <select class="inp" [(ngModel)]="global!.jefeDivisionTitulo" name="jefeDivisionTitulo">
                <option value="JEFE">JEFE</option>
                <option value="JEFA">JEFA</option>
              </select>
            </div>
            <div class="campo full">
              <label class="lbl">Imagen anual TECNM (pie de página)</label>
              <div class="imagen-anual-row">
                <span class="imagen-estado" [class.ok]="global!.tieneImagenAnual">
                  {{ global!.tieneImagenAnual ? '✓ Imagen personalizada cargada' : 'Usando imagen por defecto del sistema' }}
                </span>
                <label class="btn-secundario">
                  Subir nueva imagen
                  <input type="file" accept="image/*" (change)="onImagenAnualChange($event)" style="display:none"/>
                </label>
              </div>
              <p *ngIf="imagenNombre" class="archivo-nombre">Archivo seleccionado: {{ imagenNombre }}</p>
              <p *ngIf="imagenError" class="msg-error">{{ imagenError }}</p>
            </div>
            <div class="campo full acciones">
              <button class="btn-guardar" type="submit" [disabled]="globalGuardando">
                {{ globalGuardando ? 'Guardando…' : 'Guardar datos globales' }}
              </button>
              <span *ngIf="globalOk" class="msg-ok">&#10003; Guardado</span>
            </div>
          </form>
        </section>

        <!-- ── Sección Departamentos ──────────────────────────────────────── -->
        <section class="seccion">
          <h2 class="sec-titulo">Jefes de departamento</h2>
          <p class="sec-desc">Configura el nombre, cargo e iniciales del jefe de cada departamento académico. Aparecen en la firma de los formatos.</p>
          <div *ngIf="deptLoading" class="loading">Cargando...</div>
          <div *ngFor="let dept of departamentos; let i = index" class="dept-card">
            <h3 class="dept-titulo">{{ dept.nombre || dept.slug }}</h3>
            <div class="form-grid">
              <div class="campo">
                <label class="lbl">Nombre del Jefe</label>
                <input class="inp inp-uc" type="text" [(ngModel)]="dept.jefeNombre" [name]="'jefeNombre' + i"
                  placeholder="Ej. MANUEL FABIAN ROJAS"
                  (blur)="dept.jefeNombre = dept.jefeNombre.toUpperCase()"/>
              </div>
              <div class="campo">
                <label class="lbl">Cargo completo</label>
                <input class="inp inp-uc" type="text" [(ngModel)]="dept.jefeCargo" [name]="'jefeCargo' + i"
                  placeholder="Ej. JEFE DEL DEPARTAMENTO DE DIVISIÓN DE ESTUDIOS PROFESIONALES"
                  (blur)="dept.jefeCargo = dept.jefeCargo.toUpperCase()"/>
              </div>
              <div class="campo">
                <label class="lbl">Iniciales (pie de página)</label>
                <input class="inp" type="text" [(ngModel)]="dept.jefeIniciales" [name]="'jefeIniciales' + i"
                  placeholder="Ej. CEMB/cvr"/>
              </div>
              <div class="campo acciones">
                <button class="btn-guardar" type="button" (click)="guardarDepartamento(dept)"
                  [disabled]="dept._guardando">
                  {{ dept._guardando ? 'Guardando…' : 'Guardar' }}
                </button>
                <span *ngIf="dept._ok" class="msg-ok">&#10003; Guardado</span>
              </div>
            </div>
          </div>
        </section>
      </div>
    </div>
  `,
  styles: [`
    .page { min-height: 100vh; background: #f3f6fa; }
    .contenido { max-width: 860px; margin: 0 auto; padding: 0.75rem 1.2rem 2rem; }
    .titulo { font-size: 1.75rem; font-weight: 700; color: #1f2937; margin: 0 0 0.25rem; }
    .subtitulo { font-size: 0.95rem; color: #4b5563; margin: 0 0 1.2rem; }
    .seccion { background: #fff; border: 1px solid #d1dce8; border-radius: 12px; padding: 1.2rem 1.4rem; margin-bottom: 1.2rem; }
    .sec-titulo { font-size: 1.2rem; font-weight: 700; color: #1f2937; margin: 0 0 0.2rem; }
    .sec-desc { font-size: 0.9rem; color: #6b7280; margin: 0 0 1rem; }
    .loading { color: #6b7280; font-size: 0.95rem; }
    .form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0.75rem 1.2rem; }
    .campo { display: flex; flex-direction: column; gap: 0.25rem; }
    .campo.full { grid-column: 1 / -1; }
    .campo.acciones { flex-direction: row; align-items: center; gap: 0.75rem; justify-content: flex-start; grid-column: 1 / -1; margin-top: 0.25rem; }
    .lbl { font-size: 0.85rem; font-weight: 600; color: #374151; }
    .inp { border: 1px solid #c7d3e0; border-radius: 7px; padding: 0.45rem 0.65rem; font-size: 0.9rem; color: #111; background: #fff; outline: none; width: 100%; }
    .inp:focus { border-color: #1f4c8f; box-shadow: 0 0 0 2px rgba(31,76,143,0.13); }
    .inp-uc { text-transform: uppercase; }
    .imagen-anual-row { display: flex; align-items: center; gap: 1rem; flex-wrap: wrap; margin-top: 0.25rem; }
    .imagen-estado { font-size: 0.88rem; color: #6b7280; padding: 0.3rem 0.6rem; background: #f1f5f9; border-radius: 6px; }
    .imagen-estado.ok { color: #166534; background: #dcfce7; }
    .btn-secundario { font-size: 0.88rem; border: 1px solid #1f4c8f; color: #1f4c8f; background: #fff; border-radius: 7px; padding: 0.35rem 0.8rem; cursor: pointer; }
    .archivo-nombre { font-size: 0.82rem; color: #374151; margin: 0.3rem 0 0; }
    .btn-guardar { background: #1f4c8f; color: #fff; border: none; border-radius: 8px; padding: 0.5rem 1.3rem; font-size: 0.9rem; font-weight: 600; cursor: pointer; white-space: nowrap; }
    .btn-guardar:disabled { opacity: 0.6; cursor: default; }
    .btn-guardar:not(:disabled):hover { background: #163a70; }
    .msg-ok { font-size: 0.88rem; color: #166534; }
    .msg-error { font-size: 0.85rem; color: #b91c1c; margin: 0.25rem 0 0; }
    .dept-card { border: 1px solid #e5eaf1; border-radius: 10px; padding: 1rem 1.2rem; margin-bottom: 0.8rem; background: #f9fbfd; }
    .dept-titulo { font-size: 1rem; font-weight: 700; color: #1f4c8f; margin: 0 0 0.8rem; text-transform: capitalize; }
    @media (max-width: 640px) {
      .form-grid { grid-template-columns: 1fr; }
      .campo.full { grid-column: 1; }
      .campo.acciones { grid-column: 1; }
    }
  `],
})
export class ConfigInstitucionalComponent implements OnInit {
  global: (ConfigGlobalResponse & { _file?: File }) | null = null;
  globalLoading = true;
  globalGuardando = false;
  globalOk = false;
  imagenNombre = '';
  imagenError = '';

  departamentos: (ConfigDepartamentoResponse & { _guardando?: boolean; _ok?: boolean })[] = [];
  deptLoading = true;

  constructor(
    private svc: ConfigInstitucionalService,
    private router: Router,
  ) {}

  ngOnInit(): void {
    this.svc.getGlobal().subscribe({
      next: (r) => { this.global = r; this.globalLoading = false; },
      error: () => { this.globalLoading = false; },
    });
    this.svc.getDepartamentos().subscribe({
      next: (r) => { this.departamentos = r; this.deptLoading = false; },
      error: () => { this.deptLoading = false; },
    });
  }

  onImagenAnualChange(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    this.imagenError = '';
    if (!file) return;
    if (!file.type.startsWith('image/')) {
      this.imagenError = 'El archivo debe ser una imagen (PNG, JPG, etc.).';
      return;
    }
    this.imagenNombre = file.name;
    this.globalGuardando = true;
    this.svc.subirImagenAnual(file).subscribe({
      next: (r) => {
        if (this.global) { this.global.tieneImagenAnual = r.tieneImagenAnual; }
        this.globalGuardando = false;
        this.globalOk = true;
        setTimeout(() => (this.globalOk = false), 3000);
      },
      error: () => {
        this.imagenError = 'No se pudo subir la imagen. Intenta de nuevo.';
        this.globalGuardando = false;
      },
    });
  }

  guardarGlobal(): void {
    if (!this.global) return;
    this.globalGuardando = true;
    this.globalOk = false;
    this.svc.putGlobal({
      jefeDivisionNombre: this.global.jefeDivisionNombre.toUpperCase(),
      jefeDivisionTitulo: this.global.jefeDivisionTitulo,
    }).subscribe({
      next: (r) => {
        if (this.global) {
          this.global.jefeDivisionNombre = r.jefeDivisionNombre;
          this.global.jefeDivisionTitulo = r.jefeDivisionTitulo;
        }
        this.globalGuardando = false;
        this.globalOk = true;
        setTimeout(() => (this.globalOk = false), 3000);
      },
      error: () => { this.globalGuardando = false; },
    });
  }

  guardarDepartamento(dept: ConfigDepartamentoResponse & { _guardando?: boolean; _ok?: boolean }): void {
    dept._guardando = true;
    dept._ok = false;
    this.svc.putDepartamento(dept.slug, {
      departamentoNombreCompleto: dept.departamentoNombreCompleto,
      jefeNombre: dept.jefeNombre.toUpperCase(),
      jefeCargo: dept.jefeCargo.toUpperCase(),
      jefeIniciales: dept.jefeIniciales,
    }).subscribe({
      next: () => {
        dept._guardando = false;
        dept._ok = true;
        setTimeout(() => (dept._ok = false), 3000);
      },
      error: () => { dept._guardando = false; },
    });
  }

  volver(): void {
    this.router.navigate(['/home']);
  }
}
