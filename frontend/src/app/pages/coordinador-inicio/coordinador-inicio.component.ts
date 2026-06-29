import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { HeaderComponent } from '../../layout/header/header.component';
import { AuthService } from '../../services/auth.service';

@Component({
  selector: 'app-coordinador-inicio',
  standalone: true,
  imports: [CommonModule, HeaderComponent],
  template: `
    <div class="coordinador-inicio">
      <app-header [showNuevoButton]="false" [showAgregarUsuarioButton]="false"></app-header>
      <div class="contenido">
        <h1 class="titulo">Bienvenido</h1>
        <div class="cards">
          <button type="button" class="card" (click)="irAltaEgresadosUsuarios()">
            <div class="card-icono" aria-hidden="true">
              <svg viewBox="0 0 24 24" class="card-icono-svg">
                <path
                  d="M7 3h7l5 5v13a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1Zm6 1.5V9h4.5"
                  fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"
                />
                <path d="M9 13h6M9 17h6" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
                <path d="M12 10v6M9 13h6" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
              </svg>
            </div>
            <div class="card-texto">
              <h2>{{ tituloCardAlta }}</h2>
              <p>{{ descripcionCardAlta }}</p>
            </div>
          </button>
          <button type="button" class="card" *ngIf="!esApoyoTitulacion" (click)="irAltaDocentes()">
            <div class="card-icono" aria-hidden="true">
              <svg viewBox="0 0 24 24" class="card-icono-svg">
                <circle cx="9" cy="7" r="3" fill="none" stroke="currentColor" stroke-width="1.8"/>
                <path d="M3 20c0-3.3 2.7-6 6-6s6 2.7 6 6" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/>
                <path d="M16 11h6M19 8v6" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/>
              </svg>
            </div>
            <div class="card-texto">
              <h2>Alta de docentes</h2>
              <p>Registra docentes con nombre, correo y cédula para asignarlos como sinodales.</p>
            </div>
          </button>
          <button type="button" class="card" (click)="irSeguimientoProceso()">
            <div class="card-icono" aria-hidden="true">
              <svg viewBox="0 0 24 24" class="card-icono-svg">
                <path d="M4 19h16M7 16v-4M12 16V8M17 16v-6" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/>
                <path d="m6 8 4-3 3 2 5-3" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
            </div>
            <div class="card-texto">
              <h2>Seguimiento del proceso de titulación</h2>
              <p>Envíos a académicos, anexos 9.1 / 9.3, sinodales y acto protocolario.</p>
            </div>
          </button>
          <button type="button" class="card" *ngIf="!esApoyoTitulacion" (click)="irRevisiones()">
            <div class="card-icono" aria-hidden="true">
              <svg viewBox="0 0 24 24" class="card-icono-svg">
                <path d="m4 12 5 5L20 6" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
                <path d="M6 4h12a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2Z" fill="none" stroke="currentColor" stroke-width="1.6"/>
              </svg>
            </div>
            <div class="card-texto">
              <h2>Revisiones de documentos profesionales</h2>
              <p>Consulta el estado de la revisión académica.</p>
            </div>
          </button>
          <button type="button" class="card" *ngIf="!esApoyoTitulacion" (click)="irDepartamentoAcademicoMenu()">
            <div class="card-icono" aria-hidden="true">
              <svg viewBox="0 0 24 24" class="card-icono-svg">
                <path d="M4 21V8l8-5 8 5v13" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round"/>
                <path d="M9 21V12h6v9" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round"/>
              </svg>
            </div>
            <div class="card-texto">
              <h2>Departamento académico</h2>
              <p>Ingenierías, Económico-Administrativo, Carreras virtuales y más.</p>
            </div>
          </button>
          <button type="button" class="card" *ngIf="!esApoyoTitulacion" (click)="irServiciosEscolares()">
            <div class="card-icono" aria-hidden="true">
              <svg viewBox="0 0 24 24" class="card-icono-svg">
                <path d="M4 19V5a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v14" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round"/>
                <path d="M8 7h8M8 11h8M8 15h5" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/>
                <path d="M16 17l2 2 4-4" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
            </div>
            <div class="card-texto">
              <h2>Departamento de Servicios escolares</h2>
              <p>Bandeja de constancias 9.2 solicitadas por la DEP: pendientes y confirmación.</p>
            </div>
          </button>
          <button type="button" class="card" *ngIf="!esApoyoTitulacion" (click)="irGestionCuentas()">
            <div class="card-icono" aria-hidden="true">
              <svg viewBox="0 0 24 24" class="card-icono-svg">
                <circle cx="12" cy="8" r="4" fill="none" stroke="currentColor" stroke-width="1.8"/>
                <path d="M4 20c0-4 3.6-7 8-7s8 3 8 7" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/>
                <path d="M16 11l1.5 1.5L20 10" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
            </div>
            <div class="card-texto">
              <h2>Gestión de cuentas</h2>
              <p>Cambia correo o resetea contraseña de egresados con acceso bloqueado.</p>
            </div>
          </button>
          <button type="button" class="card" *ngIf="!esApoyoTitulacion" (click)="irRepositorio()">
            <div class="card-icono" aria-hidden="true">
              <svg viewBox="0 0 24 24" class="card-icono-svg">
                <path d="M4 19V5a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v14" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/>
                <path d="M4 19a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/>
                <path d="M9 7h6M9 11h6M9 15h4" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/>
              </svg>
            </div>
            <div class="card-texto">
              <h2>Repositorio de documentos profesionales</h2>
              <p>Consulta los proyectos de los egresados de las diferentes carreras y modalidades.</p>
            </div>
          </button>
          <button type="button" class="card" (click)="irConfigInstitucional()">
            <div class="card-icono" aria-hidden="true">
              <svg viewBox="0 0 24 24" class="card-icono-svg">
                <rect x="3" y="3" width="18" height="4" rx="1" fill="none" stroke="currentColor" stroke-width="1.8"/>
                <path d="M3 10h18M3 15h12M3 19h8" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/>
                <circle cx="19" cy="17" r="3" fill="none" stroke="currentColor" stroke-width="1.8"/>
                <path d="M19 15.5v1.5l1 1" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/>
              </svg>
            </div>
            <div class="card-texto">
              <h2>Configuración institucional</h2>
              <p>Actualiza la imagen anual TECNM y los jefes de departamento para los formatos XXXII y XXXIII.</p>
            </div>
          </button>
          <button type="button" class="card" *ngIf="!esApoyoTitulacion" (click)="irCatalogos()">
            <div class="card-icono" aria-hidden="true">
              <svg viewBox="0 0 24 24" class="card-icono-svg">
                <circle cx="12" cy="12" r="3" fill="none" stroke="currentColor" stroke-width="1.8"/>
                <path
                  d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1Z"
                  fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"
                />
              </svg>
            </div>
            <div class="card-texto">
              <h2>Configuración de catálogos</h2>
              <p>Administra carreras, niveles, modalidades y departamentos académicos.</p>
            </div>
          </button>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .coordinador-inicio { min-height: 100vh; background: #f4f4fb; }
    .contenido { padding: 0.75rem 1.5rem 1.75rem; }
    .titulo {
      margin: 0 0 0.8rem;
      text-align: center;
      font-family: 'Space Grotesk', sans-serif;
      font-size: 2rem;
      font-weight: 700;
      color: #1e1b4b;
      letter-spacing: -.5px;
    }
    .cards {
      max-width: 1080px;
      margin: 0.95rem auto 0;
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
      gap: 1rem;
    }
    .card {
      background: #fff;
      border: 1px solid #e8e8f4;
      border-radius: 18px;
      padding: 24px;
      display: flex;
      flex-direction: column;
      align-items: flex-start;
      text-align: left;
      cursor: pointer;
      transition: transform .3s cubic-bezier(.2,.8,.2,1),
                  box-shadow .3s ease, border-color .3s ease;
    }
    .card:hover {
      transform: translateY(-7px);
      border-color: #c4c2f7;
      box-shadow: 0 22px 40px -18px rgba(79,70,229,.45);
    }
    .card:focus-visible {
      outline: 2px solid #4f46e5;
      outline-offset: 2px;
    }
    .card-icono {
      width: 44px;
      height: 44px;
      border-radius: 14px;
      display: flex;
      align-items: center;
      justify-content: center;
      color: #4f46e5;
      background: linear-gradient(140deg, #eef0ff, #e3e6ff);
      margin-bottom: 18px;
      flex-shrink: 0;
      transition: transform .3s cubic-bezier(.2,.8,.2,1);
    }
    .card:hover .card-icono { transform: scale(1.08); }
    .card-icono-svg { width: 22px; height: 22px; }
    .card-texto h2 {
      margin: 0 0 8px;
      font-family: 'Space Grotesk', sans-serif;
      font-weight: 600;
      font-size: 17px;
      line-height: 1.25;
      letter-spacing: -.4px;
      color: #1e1b4b;
    }
    .card-texto p {
      margin: 0;
      font-family: 'Manrope', sans-serif;
      font-size: 13.5px;
      line-height: 1.55;
      color: #6b7280;
    }
    @media (max-width: 900px) {
      .contenido { padding: 0.6rem 1rem 1.3rem; }
      .titulo { font-size: 1.7rem; }
      .cards { grid-template-columns: 1fr 1fr; gap: 0.75rem; }
    }
    @media (max-width: 560px) {
      .cards { grid-template-columns: 1fr; }
      .card { padding: 20px; }
    }
    @media (max-width: 400px) {
      .titulo { font-size: 1.4rem; }
    }
  `],
})
export class CoordinadorInicioComponent {
  constructor(
    private router: Router,
    private auth: AuthService,
  ) {}

  get esApoyoTitulacion(): boolean {
    return this.auth.isApoyoTitulacion();
  }

  get puedeVerRepositorio(): boolean {
    return this.auth.puedeAccederRepositorio();
  }

  get tituloCardAlta(): string {
    return this.auth.puedeAdministrarUsuariosStaff()
      ? 'Inicio del proceso de titulación'
      : 'Inicio del proceso de titulación';
  }

  get descripcionCardAlta(): string {
    if (this.auth.puedeAdministrarUsuariosStaff()) {
      return 'Registro de egresados al proceso de titulacion por las diferentes modalidades y alta de roles';
    }
    return 'Registro de egresados al proceso de titulacion por las diferentes modalidades';
  }

  irDepartamentoAcademicoMenu(): void {
    this.router.navigate(['/home/departamento-academico']);
  }

  irServiciosEscolares(): void {
    this.router.navigate(['/servicios-escolares']);
  }

  irAltaEgresadosUsuarios(): void {
    this.router.navigate(['/home/alta']);
  }

  irAltaDocentes(): void {
    this.router.navigate(['/home/alta-docentes']);
  }

  irSeguimientoProceso(): void {
    this.router.navigate(['/home/seguimiento-proceso']);
  }

  irRevisiones(): void {
    this.router.navigate(['/home/revisiones']);
  }

  irGestionCuentas(): void {
    this.router.navigate(['/home/gestion-cuentas']);
  }

  irRepositorio(): void {
    this.router.navigate(['/repositorio']);
  }

  irCatalogos(): void {
    this.router.navigate(['/home/catalogos']);
  }

  irConfigInstitucional(): void {
    this.router.navigate(['/home/config-institucional']);
  }
}
