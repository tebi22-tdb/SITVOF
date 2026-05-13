import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { HeaderComponent } from '../../layout/header/header.component';
import { CatalogoService } from '../../services/catalogo.service';

@Component({
  selector: 'app-departamento-academico-menu',
  standalone: true,
  imports: [CommonModule, HeaderComponent],
  template: `
    <div class="dep-acad-menu">
      <app-header [showNuevoButton]="false" [showAgregarUsuarioButton]="false" [showVolverInicioButton]="true" (volverInicioClick)="volver()"></app-header>
      <div class="contenido">
        <h1 class="titulo">Departamento académico</h1>
        <p class="subtitulo"></p>
        <div class="cards">
          <button type="button" class="card" *ngFor="let s of (segmentos$ | async) ?? []" (click)="irSegmento(s.slug)">
            <div class="card-texto">
              <h2>{{ s.nombre }}</h2>
              <p>Expedientes de las carreras de este departamento.</p>
            </div>
            <div class="card-icono" aria-hidden="true">
              <svg viewBox="0 0 24 24" class="card-icono-svg">
                <path
                  d="M12 6v12M6 12h12"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="1.8"
                  stroke-linecap="round"
                />
                <path
                  d="M5 4h14a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1Z"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="1.5"
                />
              </svg>
            </div>
          </button>
        </div>
      </div>
    </div>
  `,
  styles: [
    `
      .dep-acad-menu {
        min-height: 100vh;
        background: #f3f6fa;
      }
      .contenido {
        padding: 0.75rem 1.5rem 1.75rem;
        max-width: 1080px;
        margin: 0 auto;
      }
      .titulo {
        margin: 0 0 0.35rem;
        text-align: center;
        font-size: 1.85rem;
        font-weight: 700;
        color: #1f2937;
      }
      .subtitulo {
        margin: 0 0 1rem;
        text-align: center;
        font-size: 0.95rem;
        color: #4b5563;
        line-height: 1.4;
      }
      .cards {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
        gap: 0.9rem;
      }
      .card {
        border: 1px solid #a8c3e4;
        border-radius: 14px;
        background: #aecded;
        padding: 1rem 1rem 1.05rem;
        min-height: 120px;
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 0.85rem;
        text-align: left;
        cursor: pointer;
        transition: transform 0.14s ease, box-shadow 0.14s ease;
      }
      .card:hover {
        transform: translateY(-1px);
        box-shadow: 0 8px 18px rgba(15, 23, 42, 0.12);
      }
      .card-texto h2 {
        margin: 0 0 0.35rem;
        font-size: 1.25rem;
        font-weight: 650;
        color: #1f2937;
      }
      .card-texto p {
        margin: 0;
        font-size: 0.88rem;
        color: #31445f;
        line-height: 1.35;
      }
      .card-icono {
        width: 72px;
        height: 72px;
        border-radius: 12px;
        background: rgba(255, 255, 255, 0.86);
        display: flex;
        align-items: center;
        justify-content: center;
        color: #1f4c8f;
        flex-shrink: 0;
      }
      .card-icono-svg {
        width: 36px;
        height: 36px;
      }
    `,
  ],
})
export class DepartamentoAcademicoMenuComponent {
  readonly segmentos$ = this.catalogoService.departamentos$;

  constructor(private router: Router, private catalogoService: CatalogoService) {}

  volver(): void {
    this.router.navigate(['/home']);
  }

  irSegmento(slug: string | null): void {
    if (!slug) return;
    this.router.navigate(['/home/revisiones'], { queryParams: { segmento: slug } });
  }
}
