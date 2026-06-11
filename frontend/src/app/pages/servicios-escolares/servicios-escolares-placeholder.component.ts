import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HeaderComponent } from '../../layout/header/header.component';

/** Pantalla provisional hasta implementar el módulo de servicios escolares. */
@Component({
  selector: 'app-servicios-escolares-placeholder',
  standalone: true,
  imports: [CommonModule, HeaderComponent],
  template: `
    <app-header [showNuevoButton]="false" [showAgregarUsuarioButton]="false"></app-header>
    <main class="se-wrap">
      <h1>Departamento de servicios escolares</h1>
      <p>Este módulo está en construcción. Pronto podrás gestionar aquí los trámites de control escolar.</p>
    </main>
  `,
  styles: [
    `
      .se-wrap {
        padding: 2rem 1.5rem;
        max-width: 720px;
      }
      h1 {
        font-size: 1.35rem;
        margin: 0 0 0.75rem;
      }
      p {
        margin: 0;
        color: #475569;
        line-height: 1.5;
      }
    `,
  ],
})
export class ServiciosEscolaresPlaceholderComponent {}
