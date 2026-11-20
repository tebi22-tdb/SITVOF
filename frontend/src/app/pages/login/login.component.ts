import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { AuthService } from '../../services/auth.service';
import { InactivityService } from '../../services/inactivity.service';
import { LOGO_ITVO, LOGO_TECNM } from '../../core/logos';
import { mensajeFallaConexion } from '../../core/mensaje-conexion';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './login.component.html',
  styleUrl: './login.component.css',
})
export class LoginComponent implements OnInit {
  readonly logoTecnm = LOGO_TECNM;
  readonly logoItvo = LOGO_ITVO;
  username = '';
  password = '';
  error = '';
  loading = false;

  vistaRecuperar = false;
  numeroControlRecuperar = '';
  mensajeRecuperar = '';
  errorRecuperar = '';
  enviandoRecuperar = false;

  constructor(
    private auth: AuthService,
    private router: Router,
    private route: ActivatedRoute,
    private inactivity: InactivityService,
  ) {}

  ngOnInit(): void {
    const motivo = this.route.snapshot.queryParamMap.get('motivo');
    if (motivo === 'inactividad') {
      this.error = 'Sesión cerrada por inactividad (10 minutos). Vuelve a iniciar sesión.';
    } else if (motivo === 'sesion') {
      this.error = 'Tu sesión expiró. Vuelve a iniciar sesión.';
    }
  }

  mostrarRecuperar(): void {
    this.vistaRecuperar = true;
    this.numeroControlRecuperar = '';
    this.mensajeRecuperar = '';
    this.errorRecuperar = '';
    this.error = '';
  }

  volverLogin(): void {
    this.vistaRecuperar = false;
    this.mensajeRecuperar = '';
    this.errorRecuperar = '';
  }

  onSubmitRecuperar(): void {
    this.errorRecuperar = '';
    this.mensajeRecuperar = '';
    if (!this.numeroControlRecuperar.trim()) {
      this.errorRecuperar = 'Ingresa tu número de control.';
      return;
    }
    this.enviandoRecuperar = true;
    this.auth.recuperarPassword(this.numeroControlRecuperar.trim()).subscribe({
      next: (res) => {
        this.enviandoRecuperar = false;
        this.mensajeRecuperar = res.message;
      },
      error: (err) => {
        this.enviandoRecuperar = false;
        this.errorRecuperar = mensajeFallaConexion(err) ?? 'No se pudo procesar la solicitud. Intenta de nuevo.';
      },
    });
  }

  onSubmit(): void {
    this.error = '';
    if (!this.username.trim() || !this.password) {
      this.error = 'Usuario y contraseña son obligatorios.';
      return;
    }
    this.loading = true;
    this.auth.login(this.username.trim(), this.password).subscribe({
      next: () => {
        this.loading = false;
        this.inactivity.reiniciar();
        void this.router.navigateByUrl(this.auth.rutaInicio());
      },
      error: (err) => {
        this.loading = false;
        const red = mensajeFallaConexion(err);
        const status = typeof err?.status === 'number' ? err.status : 0;
        this.error = red
          ?? ((status === 401 || status === 400 || status === 403)
            ? 'Usuario o contraseña incorrectos.'
            : 'No se pudo entrar. Intenta de nuevo.');
      },
    });
  }
}
