import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';

/**
 * No redirigir aquí con router.url: al refrescar, la URL del router aún no coincide con la del navegador
 * y mandaba a /login estando en /home, etc. La raíz '' ya redirige a login en app.routes.
 */
@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet],
  template: '<router-outlet></router-outlet>',
  styles: '',
})
export class AppComponent {}
