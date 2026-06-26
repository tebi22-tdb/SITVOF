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
            <div class="card-icono" aria-hidden="true">
              <svg viewBox="0 0 24 24" class="card-icono-svg">
                <path d="M12 6v12M6 12h12" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/>
                <path d="M5 4h14a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1Z" fill="none" stroke="currentColor" stroke-width="1.5"/>
              </svg>
            </div>
            <div class="card-texto">
              <h2>{{ s.nombre }}</h2>
              <p>Expedientes de las carreras de este departamento.</p>
            </div>
          </button>
        </div>
      </div>
    </div>
  `,
  styles: [
    `
      .dep-acad-menu { min-height: 100vh; background: #f4f4fb; }
      .contenido { padding: 0.75rem 1.5rem 1.75rem; max-width: 1080px; margin: 0 auto; }
      .titulo {
        margin: 0 0 0.35rem;
        text-align: center;
        font-family: 'Space Grotesk', sans-serif;
        font-size: 1.85rem;
        font-weight: 700;
        color: #1e1b4b;
        letter-spacing: -.5px;
      }
      .subtitulo { margin: 0 0 1rem; text-align: center; font-size: 0.95rem; color: #6b7280; line-height: 1.4; }
      .cards {
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
      .card:focus-visible { outline: 2px solid #4f46e5; outline-offset: 2px; }
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
        .titulo { font-size: 1.6rem; }
        .cards { grid-template-columns: 1fr 1fr; gap: 0.75rem; }
      }
      @media (max-width: 560px) {
        .cards { grid-template-columns: 1fr; }
        .card { padding: 20px; }
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
