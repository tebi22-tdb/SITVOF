import { Injectable, NgZone, OnDestroy } from '@angular/core';
import { Router } from '@angular/router';
import { environment } from '../../environments/environment';
import { AuthService, SIT_ACCESS_TOKEN_KEY } from './auth.service';

/** Cierra la sesión tras minutos sin actividad del usuario (solo con token en sessionStorage). */
@Injectable({ providedIn: 'root' })
export class InactivityService implements OnDestroy {
  private timer: ReturnType<typeof setTimeout> | null = null;
  private readonly eventos = ['mousedown', 'keydown', 'scroll', 'touchstart', 'click'] as const;
  private readonly onActividad = () => this.reiniciarTemporizador();

  constructor(
    private auth: AuthService,
    private router: Router,
    private zone: NgZone,
  ) {}

  iniciar(): void {
    const minutos = environment.inactivityLogoutMinutes ?? 0;
    if (minutos <= 0) return;

    this.zone.runOutsideAngular(() => {
      for (const ev of this.eventos) {
        document.addEventListener(ev, this.onActividad, { passive: true });
      }
    });
    this.reiniciarTemporizador();
  }

  /** Tras login exitoso, arranca el conteo de inactividad. */
  reiniciar(): void {
    this.reiniciarTemporizador();
  }

  private reiniciarTemporizador(): void {
    const minutos = environment.inactivityLogoutMinutes ?? 0;
    if (minutos <= 0) return;
    if (!sessionStorage.getItem(SIT_ACCESS_TOKEN_KEY)) {
      if (this.timer) clearTimeout(this.timer);
      this.timer = null;
      return;
    }

    if (this.timer) clearTimeout(this.timer);
    this.timer = setTimeout(() => this.cerrarPorInactividad(), minutos * 60_000);
  }

  private cerrarPorInactividad(): void {
    this.zone.run(() => {
      this.auth.clearLocalSession();
      this.router.navigate(['/login'], { queryParams: { motivo: 'inactividad' } });
    });
  }

  ngOnDestroy(): void {
    for (const ev of this.eventos) {
      document.removeEventListener(ev, this.onActividad);
    }
    if (this.timer) clearTimeout(this.timer);
  }
}
