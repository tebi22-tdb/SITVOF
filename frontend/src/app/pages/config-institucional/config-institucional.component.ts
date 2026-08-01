import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { HeaderComponent } from '../../layout/header/header.component';
import {
  ConfigInstitucionalService,
  ConfigGlobalResponse,
  ConfigDepartamentoResponse,
  ConfigServiciosEscolaresResponse,
  ImagenAnexoSlot,
  InsigniaAnexo,
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
        <p class="subtitulo">Administra los datos que cambian anualmente en los formatos XXXII, XXXIII y anexo 9.1.</p>

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
            <div class="campo">
              <label class="lbl">Iniciales (pie de página)</label>
              <input class="inp" type="text" [(ngModel)]="global!.jefeDivisionIniciales" name="jefeDivisionIniciales"
                placeholder="Ej. MFR/xyz"/>
            </div>
            <div class="campo">
              <label class="lbl">Correo (pie de página de anexos)</label>
              <input class="inp" type="email" [(ngModel)]="global!.jefeDivisionCorreo" name="jefeDivisionCorreo"
                placeholder="Ej. division_voaxaca@tecnm.mx"/>
            </div>
            <div class="campo full acciones">
              <button class="btn-guardar" type="submit" [disabled]="globalGuardando">
                {{ globalGuardando ? 'Guardando…' : 'Guardar datos globales' }}
              </button>
              <span *ngIf="globalOk" class="msg-ok">Guardado</span>
            </div>
          </form>
        </section>

        <!-- ── Sección Servicios Escolares ────────────────────────────────── -->
        <section class="seccion">
          <h2 class="sec-titulo">Departamento de Servicios Escolares</h2>
          <p class="sec-desc">Nombre y título del Jefe(a) del Departamento de Servicios Escolares.</p>
          <div *ngIf="seLoading" class="loading">Cargando...</div>
          <form *ngIf="!seLoading && serviciosEscolares" (ngSubmit)="guardarServiciosEscolares()" class="form-grid">
            <div class="campo">
              <label class="lbl">Nombre del Jefe(a)</label>
              <input class="inp inp-uc" type="text" [(ngModel)]="serviciosEscolares!.jefeNombre" name="seJefeNombre"
                placeholder="Ej. ROMEO ALBERTO ANGELES PEREZ"
                (blur)="serviciosEscolares!.jefeNombre = serviciosEscolares!.jefeNombre.toUpperCase()"/>
            </div>
            <div class="campo">
              <label class="lbl">Cargo completo</label>
              <input class="inp inp-uc" type="text" [(ngModel)]="serviciosEscolares!.jefeCargo" name="seJefeCargo"
                placeholder="Ej. JEFE DEL DEPARTAMENTO DE SERVICIOS ESCOLARES"
                (blur)="serviciosEscolares!.jefeCargo = serviciosEscolares!.jefeCargo.toUpperCase()"/>
            </div>
            <div class="campo full acciones">
              <button class="btn-guardar" type="submit" [disabled]="seGuardando">
                {{ seGuardando ? 'Guardando…' : 'Guardar Servicios Escolares' }}
              </button>
              <span *ngIf="seOk" class="msg-ok">Guardado</span>
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
              <div class="campo">
                <label class="lbl">Correo (pie de página de anexos)</label>
                <input class="inp" type="email" [(ngModel)]="dept.correo" [name]="'correo' + i"
                  placeholder="Ej. sistemas_voaxaca@tecnm.mx"/>
              </div>
              <div class="campo acciones">
                <button class="btn-guardar" type="button" (click)="guardarDepartamento(dept)"
                  [disabled]="dept._guardando">
                  {{ dept._guardando ? 'Guardando…' : 'Guardar' }}
                </button>
                <span *ngIf="dept._ok" class="msg-ok">Guardado</span>
              </div>
            </div>
          </div>
        </section>

        <!-- ── Sección Imágenes de los anexos ──────────────────────────────── -->
        <section class="seccion">
          <h2 class="sec-titulo">Imágenes de los anexos XXXII y XXXIII</h2>
          <p class="sec-desc">Logos del encabezado e insignias del pie de página. Si no subes una imagen personalizada, se usa la que trae el sistema por defecto.</p>
          <div *ngIf="imagenesLoading" class="loading">Cargando...</div>
          <p *ngIf="imagenAnexoError" class="msg-error">{{ imagenAnexoError }}</p>
          <div *ngIf="!imagenesLoading" class="imagenes-grid">
            <div *ngFor="let img of imagenesAnexos" class="imagen-card">
              <p class="imagen-card-etiqueta">{{ img.etiqueta }}</p>
              <span class="imagen-estado" [class.ok]="img.tieneImagenPersonalizada">
                {{ img.tieneImagenPersonalizada ? 'Imagen personalizada cargada' : 'Usando imagen por defecto del sistema' }}
              </span>
              <label class="btn-secundario">
                {{ imagenAnexoSubiendo === img.clave ? 'Subiendo…' : 'Subir nueva imagen' }}
                <input type="file" accept="image/*" [disabled]="imagenAnexoSubiendo === img.clave"
                  (change)="onImagenAnexoChange(img.clave, $event)" style="display:none"/>
              </label>
            </div>
          </div>
        </section>

        <!-- ── Sección Insignias del pie ───────────────────────────────────── -->
        <section class="seccion">
          <h2 class="sec-titulo">Insignias del pie de página</h2>
          <p class="sec-desc">
            Certificaciones que aparecen en fila en el pie de los anexos XXXII y XXXIII, en el orden de izquierda a derecha
            que se muestra aquí. Se pueden agregar, quitar y reordenar — el sistema ajusta el tamaño automáticamente
            para que todas quepan sin importar cuántas sean.
          </p>
          <div *ngIf="insigniasLoading" class="loading">Cargando...</div>
          <p *ngIf="insigniaError" class="msg-error">{{ insigniaError }}</p>
          <div *ngIf="!insigniasLoading" class="insignias-fila">
            <div *ngFor="let insignia of insignias; let i = index" class="insignia-item">
              <button type="button" class="insignia-quitar" title="Quitar insignia"
                [disabled]="insigniaOperando === insignia.id"
                (click)="quitarInsignia(insignia)">×</button>
              <img [src]="insignia.dataUri" class="insignia-thumb" alt=""/>
              <div class="insignia-mover">
                <button type="button" class="insignia-flecha" title="Mover a la izquierda"
                  [disabled]="i === 0 || !!insigniaOperando" (click)="moverInsignia(i, -1)">‹</button>
                <button type="button" class="insignia-flecha" title="Mover a la derecha"
                  [disabled]="i === insignias.length - 1 || !!insigniaOperando" (click)="moverInsignia(i, 1)">›</button>
              </div>
            </div>
            <label class="insignia-agregar" [class.disabled]="insigniaOperando === 'nueva'">
              <span>{{ insigniaOperando === 'nueva' ? 'Subiendo…' : '+ Agregar' }}</span>
              <input type="file" accept="image/*" [disabled]="insigniaOperando === 'nueva'"
                (change)="onAgregarInsignia($event)" style="display:none"/>
            </label>
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
    .imagen-estado { font-size: 0.88rem; color: #6b7280; padding: 0.3rem 0.6rem; background: #f1f5f9; border-radius: 6px; }
    .imagen-estado.ok { color: #166534; background: #dcfce7; }
    .btn-secundario { font-size: 0.88rem; border: 1px solid #1f4c8f; color: #1f4c8f; background: #fff; border-radius: 7px; padding: 0.35rem 0.8rem; cursor: pointer; }
    .btn-guardar { background: #1f4c8f; color: #fff; border: none; border-radius: 8px; padding: 0.5rem 1.3rem; font-size: 0.9rem; font-weight: 600; cursor: pointer; white-space: nowrap; }
    .btn-guardar:disabled { opacity: 0.6; cursor: default; }
    .btn-guardar:not(:disabled):hover { background: #163a70; }
    .msg-ok { font-size: 0.88rem; color: #166534; }
    .msg-error { font-size: 0.85rem; color: #b91c1c; margin: 0.25rem 0 0; }
    .dept-card { border: 1px solid #e5eaf1; border-radius: 10px; padding: 1rem 1.2rem; margin-bottom: 0.8rem; background: #f9fbfd; }
    .dept-titulo { font-size: 1rem; font-weight: 700; color: #1f4c8f; margin: 0 0 0.8rem; text-transform: capitalize; }
    .imagenes-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 0.8rem; }
    .imagen-card { border: 1px solid #e5eaf1; border-radius: 10px; padding: 0.9rem 1rem; background: #f9fbfd; display: flex; flex-direction: column; gap: 0.5rem; }
    .imagen-card-etiqueta { font-size: 0.88rem; font-weight: 600; color: #374151; margin: 0; }
    @media (max-width: 900px) {
      .imagenes-grid { grid-template-columns: 1fr 1fr; }
    }
    @media (max-width: 640px) {
      .imagenes-grid { grid-template-columns: 1fr; }
    }
    @media (max-width: 640px) {
      .form-grid { grid-template-columns: 1fr; }
      .campo.full { grid-column: 1; }
      .campo.acciones { grid-column: 1; }
    }
    .insignias-fila { display: flex; flex-wrap: wrap; align-items: flex-end; gap: 0.9rem; }
    .insignia-item { position: relative; display: flex; flex-direction: column; align-items: center; gap: 0.35rem; border: 1px solid #e5eaf1; border-radius: 10px; padding: 0.7rem 0.8rem 0.5rem; background: #f9fbfd; }
    .insignia-thumb { max-height: 2.2rem; max-width: 6rem; width: auto; height: auto; display: block; }
    .insignia-quitar { position: absolute; top: -0.5rem; right: -0.5rem; width: 1.4rem; height: 1.4rem; border-radius: 50%; border: 1px solid #d1dce8; background: #fff; color: #b91c1c; font-size: 0.9rem; line-height: 1; cursor: pointer; display: flex; align-items: center; justify-content: center; }
    .insignia-quitar:disabled { opacity: 0.5; cursor: default; }
    .insignia-mover { display: flex; gap: 0.3rem; }
    .insignia-flecha { border: 1px solid #c7d3e0; background: #fff; color: #1f4c8f; border-radius: 5px; width: 1.5rem; height: 1.5rem; font-size: 0.95rem; line-height: 1; cursor: pointer; }
    .insignia-flecha:disabled { opacity: 0.35; cursor: default; }
    .insignia-agregar { display: flex; align-items: center; justify-content: center; min-width: 5.5rem; min-height: 3.6rem; border: 1px dashed #9db2cc; border-radius: 10px; color: #1f4c8f; font-size: 0.85rem; font-weight: 600; cursor: pointer; text-align: center; padding: 0.5rem; }
    .insignia-agregar.disabled { opacity: 0.6; cursor: default; }
  `],
})
export class ConfigInstitucionalComponent implements OnInit {
  global: ConfigGlobalResponse | null = null;
  globalLoading = true;
  globalGuardando = false;
  globalOk = false;

  departamentos: (ConfigDepartamentoResponse & { _guardando?: boolean; _ok?: boolean })[] = [];
  deptLoading = true;

  serviciosEscolares: ConfigServiciosEscolaresResponse | null = null;
  seLoading = true;
  seGuardando = false;
  seOk = false;

  imagenesAnexos: ImagenAnexoSlot[] = [];
  imagenesLoading = true;
  imagenAnexoSubiendo: string | null = null;
  imagenAnexoError = '';

  insignias: InsigniaAnexo[] = [];
  insigniasLoading = true;
  /** id de la insignia sobre la que hay una operación en curso, o 'nueva' mientras se agrega una. */
  insigniaOperando: string | null = null;
  insigniaError = '';

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
    this.svc.getServiciosEscolares().subscribe({
      next: (r) => { this.serviciosEscolares = r; this.seLoading = false; },
      error: () => { this.seLoading = false; },
    });
    this.svc.getImagenesAnexos().subscribe({
      next: (r) => { this.imagenesAnexos = r; this.imagenesLoading = false; },
      error: () => { this.imagenesLoading = false; },
    });
    this.svc.getInsigniasAnexos().subscribe({
      next: (r) => { this.insignias = r; this.insigniasLoading = false; },
      error: () => { this.insigniasLoading = false; },
    });
  }

  onAgregarInsignia(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    this.insigniaError = '';
    if (!file) return;
    if (!file.type.startsWith('image/')) {
      this.insigniaError = 'El archivo debe ser una imagen (PNG, JPG, etc.).';
      return;
    }
    this.insigniaOperando = 'nueva';
    this.svc.agregarInsigniaAnexo(file).subscribe({
      next: (r) => {
        this.insignias = r;
        this.insigniaOperando = null;
        input.value = '';
      },
      error: () => {
        this.insigniaError = 'No se pudo agregar la insignia. Intenta de nuevo.';
        this.insigniaOperando = null;
      },
    });
  }

  quitarInsignia(insignia: InsigniaAnexo): void {
    if (!confirm('¿Quitar esta insignia del pie de página?')) return;
    this.insigniaError = '';
    this.insigniaOperando = insignia.id;
    this.svc.eliminarInsigniaAnexo(insignia.id).subscribe({
      next: (r) => {
        this.insignias = r;
        this.insigniaOperando = null;
      },
      error: () => {
        this.insigniaError = 'No se pudo quitar la insignia. Intenta de nuevo.';
        this.insigniaOperando = null;
      },
    });
  }

  /** Mueve la insignia en la posición `index` una casilla a la izquierda (-1) o derecha (+1). */
  moverInsignia(index: number, direccion: -1 | 1): void {
    const destino = index + direccion;
    if (destino < 0 || destino >= this.insignias.length) return;
    const copia = [...this.insignias];
    [copia[index], copia[destino]] = [copia[destino], copia[index]];
    this.insignias = copia;
    this.insigniaError = '';
    this.insigniaOperando = copia[destino].id;
    this.svc.reordenarInsigniasAnexos(copia.map((i) => i.id)).subscribe({
      next: (r) => {
        this.insignias = r;
        this.insigniaOperando = null;
      },
      error: () => {
        this.insigniaError = 'No se pudo reordenar. Intenta de nuevo.';
        this.insigniaOperando = null;
      },
    });
  }

  onImagenAnexoChange(clave: string, event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    this.imagenAnexoError = '';
    if (!file) return;
    if (!file.type.startsWith('image/')) {
      this.imagenAnexoError = 'El archivo debe ser una imagen (PNG, JPG, etc.).';
      return;
    }
    this.imagenAnexoSubiendo = clave;
    this.svc.subirImagenAnexo(clave, file).subscribe({
      next: (r) => {
        this.imagenesAnexos = r;
        this.imagenAnexoSubiendo = null;
      },
      error: (err) => {
        this.imagenAnexoError = err?.error?.error || 'No se pudo subir la imagen. Intenta de nuevo.';
        this.imagenAnexoSubiendo = null;
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
      jefeDivisionIniciales: this.global.jefeDivisionIniciales,
      jefeDivisionCorreo: this.global.jefeDivisionCorreo,
    }).subscribe({
      next: (r) => {
        if (this.global) {
          this.global.jefeDivisionNombre = r.jefeDivisionNombre;
          this.global.jefeDivisionTitulo = r.jefeDivisionTitulo;
          this.global.jefeDivisionIniciales = r.jefeDivisionIniciales;
          this.global.jefeDivisionCorreo = r.jefeDivisionCorreo;
        }
        this.globalGuardando = false;
        this.globalOk = true;
        setTimeout(() => (this.globalOk = false), 3000);
      },
      error: () => { this.globalGuardando = false; },
    });
  }

  guardarServiciosEscolares(): void {
    if (!this.serviciosEscolares) return;
    this.seGuardando = true;
    this.seOk = false;
    this.svc.putServiciosEscolares({
      jefeNombre: this.serviciosEscolares.jefeNombre.toUpperCase(),
      jefeCargo: this.serviciosEscolares.jefeCargo.toUpperCase(),
    }).subscribe({
      next: (r) => {
        this.serviciosEscolares = r;
        this.seGuardando = false;
        this.seOk = true;
        setTimeout(() => (this.seOk = false), 3000);
      },
      error: () => { this.seGuardando = false; },
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
      correo: dept.correo,
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
