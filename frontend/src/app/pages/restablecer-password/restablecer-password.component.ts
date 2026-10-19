import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AuthService } from '../../services/auth.service';
import { LOGO_ITVO, LOGO_TECNM } from '../../core/logos';

@Component({
  selector: 'app-restablecer-password',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './restablecer-password.component.html',
  styleUrl: '../login/login.component.css',
})
export class RestablecerPasswordComponent implements OnInit {
  readonly logoTecnm = LOGO_TECNM;
  readonly logoItvo = LOGO_ITVO;

  token = '';
  password = '';
  passwordConfirm = '';
  error = '';
  mensaje = '';
  enviando = false;
  tokenFaltante = false;

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private auth: AuthService,
  ) {}

  ngOnInit(): void {
    this.token = (this.route.snapshot.queryParamMap.get('token') ?? '').trim();
    if (!this.token) {
      this.tokenFaltante = true;
      this.error = 'Este enlace no es válido. Solicita uno nuevo desde «¿Olvidaste tu contraseña?»';
    }
  }

  onSubmit(): void {
    this.error = '';
    this.mensaje = '';
    if (!this.token) {
      this.error = 'Este enlace no es válido.';
      return;
    }
    if (this.password.length < 8) {
      this.error = 'La contraseña debe tener al menos 8 caracteres.';
      return;
    }
    if (this.password !== this.passwordConfirm) {
      this.error = 'Las contraseñas no coinciden.';
      return;
    }
    this.enviando = true;
    this.auth.restablecerPassword(this.token, this.password, this.passwordConfirm).subscribe({
      next: (res) => {
        this.enviando = false;
        this.mensaje = res.message;
        setTimeout(() => this.router.navigate(['/login']), 2500);
      },
      error: (err) => {
        this.enviando = false;
        const msg = err?.error?.error;
        this.error =
          typeof msg === 'string' && msg.trim()
            ? msg
            : 'No se pudo restablecer la contraseña. Solicita un enlace nuevo.';
      },
    });
  }
}
