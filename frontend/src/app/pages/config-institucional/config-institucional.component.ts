import { Component, ElementRef, OnInit, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { Router } from '@angular/router';
import { HeaderComponent } from '../../layout/header/header.component';
import {
  ConfigInstitucionalService,
  ConfigGlobalResponse,
  ConfigDepartamentoResponse,
  ConfigServiciosEscolaresResponse,
  CodigosAnexos,
  ImagenAnexoSlot,
  InsigniaAnexo,
} from '../../services/config-institucional.service';
import { textoErrorUsuario } from '../../core/mensaje-conexion';

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

        <div class="cfg-tabs" role="tablist">
          <button type="button" role="tab" class="cfg-tab"
            [class.cfg-tab-activo]="tabActivo === 'datos'"
            [attr.aria-selected]="tabActivo === 'datos'"
            (click)="tabActivo = 'datos'">Datos</button>
          <button type="button" role="tab" class="cfg-tab"
            [class.cfg-tab-activo]="tabActivo === 'formato'"
            [attr.aria-selected]="tabActivo === 'formato'"
            (click)="tabActivo = 'formato'">Formato</button>
          <button type="button" role="tab" class="cfg-tab"
            [class.cfg-tab-activo]="tabActivo === 'textos'"
            [attr.aria-selected]="tabActivo === 'textos'"
            (click)="tabActivo = 'textos'">Textos</button>
        </div>

        <ng-container *ngIf="tabActivo === 'datos'">
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
                placeholder="Ej. ACAC/lmr"/>
              <p class="campo-hint" style="margin:6px 0 0;font-size:12px;color:#6b7280;line-height:1.4;">
                La parte antes de la barra se toma del nombre del Jefe(a) (ej. Alida Corey Arango Cruz → ACAC).
                Después de la barra va quien elaboró el documento (ej. /lmr). Al cambiar el nombre, el prefijo se actualiza solo.
              </p>
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
        </ng-container>

        <ng-container *ngIf="tabActivo === 'formato'">
        <!-- ── Sección Código y revisión de anexos ────────────────────────── -->
        <section class="seccion">
          <h2 class="sec-titulo">Código y revisión de los anexos</h2>
          <p class="sec-desc">Texto del recuadro superior y del pie de los anexos 9.1, 9.2 y 9.3. Si dejas un campo vacío, se conserva el valor que ya trae el sistema.</p>
          <div *ngIf="codigosLoading" class="loading">Cargando...</div>
          <p *ngIf="codigosError" class="msg-error">{{ codigosError }}</p>
          <form *ngIf="!codigosLoading && codigos" (ngSubmit)="guardarCodigos()" class="form-grid">
            <h3 class="dept-titulo campo full">Anexo 9.1</h3>
            <div class="campo">
              <label class="lbl">Código</label>
              <input class="inp inp-uc" type="text" [(ngModel)]="codigos!.codigoAnexo91" name="codigoAnexo91"
                placeholder="ITVO-AC-PR-05-02" maxlength="80"/>
            </div>
            <div class="campo">
              <label class="lbl">Revisión</label>
              <input class="inp" type="text" [(ngModel)]="codigos!.revisionAnexo91" name="revisionAnexo91"
                placeholder="2" maxlength="20"/>
            </div>
            <h3 class="dept-titulo campo full">Anexo 9.2</h3>
            <div class="campo">
              <label class="lbl">Código</label>
              <input class="inp inp-uc" type="text" [(ngModel)]="codigos!.codigoAnexo92" name="codigoAnexo92"
                placeholder="ITVO-AC-PR-05-03" maxlength="80"/>
            </div>
            <div class="campo">
              <label class="lbl">Revisión</label>
              <input class="inp" type="text" [(ngModel)]="codigos!.revisionAnexo92" name="revisionAnexo92"
                placeholder="2" maxlength="20"/>
            </div>
            <h3 class="dept-titulo campo full">Anexo 9.3</h3>
            <div class="campo">
              <label class="lbl">Código</label>
              <input class="inp inp-uc" type="text" [(ngModel)]="codigos!.codigoAnexo93" name="codigoAnexo93"
                placeholder="ITVO-AC-PR-05-04" maxlength="80"/>
            </div>
            <div class="campo">
              <label class="lbl">Revisión</label>
              <input class="inp" type="text" [(ngModel)]="codigos!.revisionAnexo93" name="revisionAnexo93"
                placeholder="2" maxlength="20"/>
            </div>
            <div class="campo full acciones">
              <button class="btn-guardar" type="submit" [disabled]="codigosGuardando">
                {{ codigosGuardando ? 'Guardando…' : 'Guardar códigos y revisiones' }}
              </button>
              <span *ngIf="codigosOk" class="msg-ok">Guardado</span>
            </div>
          </form>
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
        </ng-container>

        <ng-container *ngIf="tabActivo === 'textos'">
        <section class="seccion">
          <h2 class="sec-titulo">Textos de los anexos</h2>
          <p class="sec-desc">Selecciona y edita los anexos.</p>
          <div class="textos-botones">
            <button type="button" class="btn-anexo" *ngFor="let a of anexosTexto"
              [class.btn-anexo-activo]="anexoTextoActivo === a.id"
              (click)="abrirAnexoTexto(a.id)">{{ a.etiqueta }}</button>
          </div>
          <p *ngIf="textosError" class="msg-error">{{ textosError }}</p>
          <div *ngIf="textosCargando" class="loading">Cargando la hoja...</div>
          <div class="barra-formato" *ngIf="vistaTextos">
            <select class="inp inp-corto" aria-label="Tipo de letra" (change)="aplicarTipo($any($event.target))">
              <option value="">Tipo de letra</option>
              <option value="Arial">Arial</option>
              <option value="Times New Roman">Times</option>
              <option value="Estrecha">Estrecha</option>
            </select>
            <select class="inp inp-corto" aria-label="Tamaño de letra" (change)="aplicarTamano($any($event.target))">
              <option value="">Tamaño</option>
              <option value="10pt">Chica</option>
              <option value="12pt">Normal</option>
              <option value="14pt">Grande</option>
            </select>
            <button type="button" class="btn-sub" (mousedown)="$event.preventDefault()" (click)="aplicarSubrayado()">Subrayado</button>
            <button type="button" class="btn-cursiva" (mousedown)="$event.preventDefault()" (click)="aplicarCursiva()">Cursiva</button>
            <button type="button" class="btn-align" title="Izquierda" aria-label="Alinear a la izquierda" (mousedown)="$event.preventDefault()" (click)="aplicarAlineacion('left')">
              <svg viewBox="0 0 18 14" aria-hidden="true"><rect x="0" y="0" width="18" height="2" rx="0.6"/><rect x="0" y="4" width="12" height="2" rx="0.6"/><rect x="0" y="8" width="18" height="2" rx="0.6"/><rect x="0" y="12" width="8" height="2" rx="0.6"/></svg>
            </button>
            <button type="button" class="btn-align" title="Centro" aria-label="Centrar" (mousedown)="$event.preventDefault()" (click)="aplicarAlineacion('center')">
              <svg viewBox="0 0 18 14" aria-hidden="true"><rect x="0" y="0" width="18" height="2" rx="0.6"/><rect x="3" y="4" width="12" height="2" rx="0.6"/><rect x="0" y="8" width="18" height="2" rx="0.6"/><rect x="5" y="12" width="8" height="2" rx="0.6"/></svg>
            </button>
            <button type="button" class="btn-align" title="Derecha" aria-label="Alinear a la derecha" (mousedown)="$event.preventDefault()" (click)="aplicarAlineacion('right')">
              <svg viewBox="0 0 18 14" aria-hidden="true"><rect x="0" y="0" width="18" height="2" rx="0.6"/><rect x="6" y="4" width="12" height="2" rx="0.6"/><rect x="0" y="8" width="18" height="2" rx="0.6"/><rect x="10" y="12" width="8" height="2" rx="0.6"/></svg>
            </button>
            <button type="button" class="btn-align" title="Justificado" aria-label="Justificar" (mousedown)="$event.preventDefault()" (click)="aplicarAlineacion('justify')">
              <svg viewBox="0 0 18 14" aria-hidden="true"><rect x="0" y="0" width="18" height="2" rx="0.6"/><rect x="0" y="4" width="18" height="2" rx="0.6"/><rect x="0" y="8" width="18" height="2" rx="0.6"/><rect x="0" y="12" width="18" height="2" rx="0.6"/></svg>
            </button>
            <button type="button" class="btn-color btn-color-negro" title="Negro" aria-label="Color negro" (mousedown)="$event.preventDefault()" (click)="aplicarColor('#000000')"></button>
            <button type="button" class="btn-color btn-color-azul" title="Azul" aria-label="Color azul" (mousedown)="$event.preventDefault()" (click)="aplicarColor('#1f4c8f')"></button>
            <button type="button" class="btn-color btn-color-rojo" title="Rojo" aria-label="Color rojo" (mousedown)="$event.preventDefault()" (click)="aplicarColor('#b91c1c')"></button>
            <button type="button" class="btn-color btn-color-blanco" title="Blanco" aria-label="Color blanco" (mousedown)="$event.preventDefault()" (click)="aplicarColor('#ffffff')"></button>
          </div>
          <div class="hoja-marco" *ngIf="vistaTextos">
            <iframe #hojaTextos class="hoja-frame" title="Anexo" [srcdoc]="vistaTextos" (load)="engancharHoja()"></iframe>
          </div>
          <div class="campo acciones" *ngIf="vistaTextos">
            <button type="button" class="btn-guardar" [disabled]="textosGuardando" (click)="pedirGuardarTextos()">
              {{ textosGuardando ? 'Guardando…' : 'Guardar' }}
            </button>
            <span *ngIf="textosOk" class="msg-ok">Guardado</span>
          </div>
        </section>

        <div class="cfg-modal" *ngIf="confirmarTextos" role="dialog" aria-modal="true" aria-labelledby="confirmar-textos-titulo">
          <div class="cfg-modal-caja">
            <h3 id="confirmar-textos-titulo" class="sec-titulo">Guardar textos</h3>
            <p class="sec-desc">¿Estás seguro de guardar estos cambios?</p>
            <div class="campo acciones">
              <button type="button" class="btn-guardar" [disabled]="textosGuardando" (click)="confirmarGuardarTextos()">Sí, guardar</button>
              <button type="button" class="btn-secundario" [disabled]="textosGuardando" (click)="confirmarTextos = false">Cancelar</button>
            </div>
          </div>
        </div>
        </ng-container>
      </div>
    </div>
  `,
  styles: [`
    .page { min-height: 100vh; background: #f3f6fa; }
    .contenido { max-width: 860px; margin: 0 auto; padding: 0.75rem 1.2rem 2rem; }
    .titulo { font-size: 1.75rem; font-weight: 700; color: #1f2937; margin: 0 0 1rem; }
    .cfg-tabs { display: flex; gap: 0.25rem; border-bottom: 2px solid #cbd5e1; margin-bottom: 1rem; flex-wrap: wrap; }
    .cfg-tab { padding: 0.55rem 1.1rem; border: none; background: none; font-family: inherit; font-size: 0.95rem; font-weight: 500; color: #64748b; cursor: pointer; border-bottom: 2px solid transparent; margin-bottom: -2px; border-radius: 4px 4px 0 0; }
    .cfg-tab:hover { color: #1f4c8f; }
    .cfg-tab-activo { color: #1f4c8f; border-bottom-color: #1f4c8f; font-weight: 600; }
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
    .form-grid > h3.dept-titulo { margin: 0.85rem 0 0; text-transform: none; }
    .form-grid > h3.dept-titulo:first-child { margin-top: 0; }
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
    .textos-botones { display: flex; flex-wrap: wrap; gap: 0.5rem; margin-bottom: 0.9rem; }
    .barra-formato { display: flex; flex-wrap: wrap; align-items: center; gap: 0.45rem; margin: 0 0 0.75rem; }
    .inp-corto { width: auto; min-width: 8.5rem; }
    .btn-sub { border: 1px solid #1f4c8f; background: #fff; color: #1f4c8f; border-radius: 7px; padding: 0.4rem 0.75rem; font-weight: 700; text-decoration: underline; cursor: pointer; }
    .btn-cursiva { border: 1px solid #1f4c8f; background: #fff; color: #1f4c8f; border-radius: 7px; padding: 0.4rem 0.75rem; font-weight: 700; font-style: italic; cursor: pointer; }
    .btn-align { width: 2.15rem; height: 2.15rem; border: 1px solid #c5c9d0; background: #f3f4f6; color: #3a3a3a; border-radius: 6px; padding: 0.32rem; cursor: pointer; display: inline-flex; align-items: center; justify-content: center; }
    .btn-align svg { width: 18px; height: 14px; display: block; }
    .btn-align rect { fill: currentColor; }
    .btn-color { width: 1.7rem; height: 1.7rem; border-radius: 50%; border: 2px solid #fff; box-shadow: 0 0 0 1px #c7d3e0; cursor: pointer; padding: 0; }
    .btn-color-negro { background: #000; }
    .btn-color-azul { background: #1f4c8f; }
    .btn-color-rojo { background: #b91c1c; }
    .btn-color-blanco { background: #fff; }
    .btn-anexo { border: 1px solid #1f4c8f; background: #fff; color: #1f4c8f; border-radius: 8px; padding: 0.4rem 0.9rem; font-weight: 600; cursor: pointer; }
    .btn-anexo-activo { background: #1f4c8f; color: #fff; }
    .hoja-marco { background: #e8eef5; border-radius: 10px; padding: 12px; overflow: auto; max-height: 78vh; }
    .hoja-frame { width: 820px; height: 1120px; border: 1px solid #c7d3e0; background: #fff; display: block; margin: 0 auto; }
    .cfg-modal { position: fixed; inset: 0; background: rgba(15, 23, 42, 0.45); display: flex; align-items: center; justify-content: center; z-index: 40; padding: 1rem; }
    .cfg-modal-caja { background: #fff; border-radius: 12px; padding: 1.3rem 1.4rem; max-width: 440px; width: 100%; box-shadow: 0 12px 40px rgba(15, 23, 42, 0.18); }
  `],
})
export class ConfigInstitucionalComponent implements OnInit {
  tabActivo: 'datos' | 'formato' | 'textos' = 'datos';

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

  codigos: CodigosAnexos | null = null;
  codigosLoading = true;
  codigosGuardando = false;
  codigosOk = false;
  codigosError = '';

  imagenesAnexos: ImagenAnexoSlot[] = [];
  imagenesLoading = true;
  imagenAnexoSubiendo: string | null = null;
  imagenAnexoError = '';

  insignias: InsigniaAnexo[] = [];
  insigniasLoading = true;
  /** id de la insignia sobre la que hay una operación en curso, o 'nueva' mientras se agrega una. */
  insigniaOperando: string | null = null;
  insigniaError = '';

  anexosTexto = [
    { id: '32', etiqueta: 'XXXII' },
    { id: '33', etiqueta: 'XXXIII' },
    { id: '9.1', etiqueta: '9.1' },
    { id: '9.2', etiqueta: '9.2' },
    { id: '9.3', etiqueta: '9.3' },
  ];
  anexoTextoActivo = '';
  vistaTextos: SafeHtml | null = null;
  textosCargando = false;
  textosGuardando = false;
  textosOk = false;
  textosError = '';
  confirmarTextos = false;
  private bloquesPendientes: Record<string, string> | null = null;
  private rangoTexto: Range | null = null;

  @ViewChild('hojaTextos') hojaTextos?: ElementRef<HTMLIFrameElement>;

  constructor(
    private svc: ConfigInstitucionalService,
    private router: Router,
    private sanitizer: DomSanitizer,
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
    this.svc.getCodigosAnexos().subscribe({
      next: (r) => { this.codigos = r; this.codigosLoading = false; },
      error: (err) => {
        this.codigosLoading = false;
        this.codigosError = textoErrorUsuario(err, 'No se pudieron cargar los códigos.');
      },
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
      error: (err) => {
        this.insigniaError = textoErrorUsuario(err, 'No se pudo agregar la insignia. Intenta de nuevo.');
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
      error: (err) => {
        this.insigniaError = textoErrorUsuario(err, 'No se pudo quitar la insignia. Intenta de nuevo.');
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
      error: (err) => {
        this.insigniaError = textoErrorUsuario(err, 'No se pudo reordenar. Intenta de nuevo.');
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
        this.imagenAnexoError = textoErrorUsuario(err, 'No se pudo subir la imagen. Intenta de nuevo.');
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

  guardarCodigos(): void {
    if (!this.codigos) return;
    this.codigosGuardando = true;
    this.codigosOk = false;
    this.codigosError = '';
    this.svc.putCodigosAnexos({
      codigoAnexo91: this.codigos.codigoAnexo91.trim().toUpperCase(),
      revisionAnexo91: this.codigos.revisionAnexo91.trim(),
      codigoAnexo92: this.codigos.codigoAnexo92.trim().toUpperCase(),
      revisionAnexo92: this.codigos.revisionAnexo92.trim(),
      codigoAnexo93: this.codigos.codigoAnexo93.trim().toUpperCase(),
      revisionAnexo93: this.codigos.revisionAnexo93.trim(),
    }).subscribe({
      next: (r) => {
        this.codigos = r;
        this.codigosGuardando = false;
        this.codigosOk = true;
        setTimeout(() => (this.codigosOk = false), 3000);
      },
      error: (err) => {
        this.codigosGuardando = false;
        this.codigosError = textoErrorUsuario(err, 'No se pudieron guardar los códigos.');
      },
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

  abrirAnexoTexto(id: string): void {
    this.anexoTextoActivo = id;
    this.vistaTextos = null;
    this.rangoTexto = null;
    this.textosOk = false;
    this.textosError = '';
    this.confirmarTextos = false;
    this.textosCargando = true;
    this.svc.vistaTextosAnexo(id).subscribe({
      next: (html) => {
        this.vistaTextos = this.sanitizer.bypassSecurityTrustHtml(html);
        this.textosCargando = false;
      },
      error: (err) => {
        this.textosCargando = false;
        this.textosError = textoErrorUsuario(err, 'No se pudo abrir el anexo.');
      },
    });
  }

  engancharHoja(): void {
    const doc = this.hojaTextos?.nativeElement.contentDocument;
    if (!doc) return;
    const guardar = () => this.guardarRango();
    doc.addEventListener('mouseup', guardar);
    doc.addEventListener('keyup', guardar);
  }

  guardarRango(): void {
    const sel = this.hojaTextos?.nativeElement.contentWindow?.getSelection();
    if (!sel || sel.rangeCount === 0 || sel.isCollapsed) return;
    const rango = sel.getRangeAt(0);
    if (this.rangoProhibido(rango)) {
      this.rangoTexto = null;
      return;
    }
    this.rangoTexto = rango.cloneRange();
  }

  private rangoProhibido(rango: Range): boolean {
    const nodo = rango.commonAncestorContainer;
    const el = nodo.nodeType === Node.ELEMENT_NODE ? (nodo as Element) : nodo.parentElement;
    if (!el) return true;
    return !!el.closest('.hdr-fixed, .ftr-fixed, .qr-auth-wrap, .qr-anchor, .firma-qr-right, .ph-fijo');
  }

  aplicarTipo(select: HTMLSelectElement): void {
    const valor = select.value;
    select.value = '';
    if (!valor) return;
    this.aplicarEstilo({ 'font-family': valor === 'Times New Roman' ? "'Times New Roman'" : valor });
  }

  aplicarTamano(select: HTMLSelectElement): void {
    const valor = select.value;
    select.value = '';
    if (!valor) return;
    this.aplicarEstilo({ 'font-size': valor });
  }

  aplicarSubrayado(): void {
    this.aplicarEstilo({ 'text-decoration': 'underline' });
  }

  aplicarCursiva(): void {
    this.aplicarEstilo({ 'font-style': 'italic' });
  }

  aplicarAlineacion(valor: 'left' | 'center' | 'right' | 'justify'): void {
    const doc = this.hojaTextos?.nativeElement.contentDocument;
    const rango = this.rangoTexto;
    const nodo = rango?.commonAncestorContainer;
    const base = nodo && (nodo.nodeType === Node.ELEMENT_NODE ? (nodo as Element) : nodo.parentElement);
    const bloque = base?.closest('[data-texto]') as HTMLElement | null;
    if (!doc || !rango || !bloque || this.rangoProhibido(rango)) {
      this.textosError = 'Selecciona el texto dentro del párrafo.';
      return;
    }
    this.textosError = '';
    let caja = bloque.firstElementChild as HTMLElement | null;
    const estilo = caja?.getAttribute('style') || '';
    const esCaja = caja?.tagName === 'SPAN' && estilo.includes('text-align') && bloque.childNodes.length === 1;
    if (!esCaja || !caja) {
      caja = doc.createElement('span');
      while (bloque.firstChild) caja.appendChild(bloque.firstChild);
      bloque.appendChild(caja);
    }
    caja.setAttribute('style', `display: block; text-align: ${valor}`);
  }

  aplicarColor(color: string): void {
    this.aplicarEstilo({ color });
  }

  private aplicarEstilo(declaraciones: Record<string, string>): void {
    const doc = this.hojaTextos?.nativeElement.contentDocument;
    const rango = this.rangoTexto;
    if (!doc || !rango || rango.collapsed) {
      this.textosError = 'Selecciona el texto dentro del párrafo.';
      return;
    }
    this.textosError = '';
    const span = doc.createElement('span');
    span.setAttribute('style', Object.entries(declaraciones).map(([k, v]) => `${k}: ${v}`).join('; '));
    try {
      rango.surroundContents(span);
    } catch {
      const contenido = rango.extractContents();
      span.appendChild(contenido);
      rango.insertNode(span);
    }
    const sel = doc.defaultView?.getSelection();
    sel?.removeAllRanges();
    const nuevo = doc.createRange();
    nuevo.selectNodeContents(span);
    sel?.addRange(nuevo);
    this.rangoTexto = nuevo.cloneRange();
  }

  pedirGuardarTextos(): void {
    const doc = this.hojaTextos?.nativeElement.contentDocument;
    if (!doc) {
      this.textosError = 'La hoja todavía no está lista.';
      return;
    }
    const bloques: Record<string, string> = {};
    doc.querySelectorAll('[data-texto]').forEach((el) => {
      const id = el.getAttribute('data-texto');
      if (id) bloques[id] = (el as HTMLElement).innerHTML;
    });
    this.bloquesPendientes = bloques;
    this.confirmarTextos = true;
  }

  confirmarGuardarTextos(): void {
    if (!this.anexoTextoActivo || !this.bloquesPendientes) return;
    this.textosGuardando = true;
    this.textosOk = false;
    this.textosError = '';
    this.svc.guardarTextosAnexo(this.anexoTextoActivo, this.bloquesPendientes).subscribe({
      next: () => {
        this.textosGuardando = false;
        this.confirmarTextos = false;
        this.bloquesPendientes = null;
        this.textosOk = true;
        setTimeout(() => (this.textosOk = false), 3000);
      },
      error: (err) => {
        this.textosGuardando = false;
        this.confirmarTextos = false;
        this.textosError = textoErrorUsuario(err, 'No se pudieron guardar los textos.');
      },
    });
  }

  volver(): void {
    this.router.navigate(['/home']);
  }
}
