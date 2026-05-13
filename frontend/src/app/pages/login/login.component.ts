import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthService } from '../../services/auth.service';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './login.component.html',
  styleUrl: './login.component.css',
})
export class LoginComponent {
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
    private router: Router
  ) {}

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
      error: () => {
        this.enviandoRecuperar = false;
        this.errorRecuperar = 'No se pudo procesar la solicitud. Intenta de nuevo.';
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
      next: (user) => {
        this.loading = false;
        const rol = (user?.rol ?? '').toLowerCase();
        if (rol === 'servicios_escolares') {
          this.router.navigate(['/servicios-escolares']);
        } else if (rol === 'academico') {
          this.router.navigate(['/departamento-academico']);
        } else if (this.auth.isCoordinador()) {
          this.router.navigate(['/home']);
        } else {
          this.router.navigate(['/seguimiento']);
        }
      },
      error: () => {
        this.loading = false;
        this.error = 'Usuario o contraseña incorrectos.';
      },
    });
  }
}
