import { Component, EventEmitter, Input, Output, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { AuthService } from '../../services/auth.service';
import { CatalogoService } from '../../services/catalogo.service';

@Component({
  selector: 'app-header',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './header.component.html',
  styleUrl: './header.component.css',
})
export class HeaderComponent implements OnInit {
  nombreUsuario = '';
  /** Si es false, no se muestra el botón "Nuevo" (ej. en la interfaz de seguimiento del egresado). */
  @Input() showNuevoButton = true;
  @Output() nuevoClick = new EventEmitter<void>();
  /** Muestra el botón "Agregar usuario" (solo en home para coordinador). */
  @Input() showAgregarUsuarioButton = false;
  @Output() agregarUsuarioClick = new EventEmitter<void>();
  /** Muestra botón para volver al inicio principal (/home). */
  @Input() showVolverInicioButton = false;
  @Output() volverInicioClick = new EventEmitter<void>();

  constructor(
    private auth: AuthService,
    private router: Router,
    private catalogoService: CatalogoService,
  ) {}

  ngOnInit(): void {
    const u = this.auth.getUsuario();
    const username = u?.username ?? '';
    const rol = (u?.rol ?? '').toLowerCase();
    const nombre = this.obtenerNombreMostrable(u?.nombre, username);

    if (rol === 'academico') {
      const area =
        this.catalogoService.nombreDepartamentoPorSlug(u?.segmento_academico ?? '') ??
        this.catalogoService.inferirNombreDepartamentoPorCarreras(u?.carreras_asignadas ?? []) ??
        'Coordinacion de apoyo a la titulacion';
      this.nombreUsuario = `${nombre} - ${area}`;
    } else if (username === 'coordinador' || rol === 'coordinador') {
      this.nombreUsuario = `${nombre} - Administrador`;
    } else if (rol === 'apoyo_titulacion') {
      this.nombreUsuario = `${nombre} - Apoyo titulación`;
    } else if (rol === 'division_estudios_prof_admin') {
      this.nombreUsuario = `${nombre} - Administrativo`;
    } else if (rol === 'servicios_escolares') {
      this.nombreUsuario = `${nombre} - Servicios escolares`;
    } else {
      this.nombreUsuario = nombre;
    }
  }

  private obtenerNombreMostrable(nombre: string | undefined, username: string): string {
    const limpio = (nombre ?? '').trim();
    if (limpio) return limpio;
    const base = username.split('@')[0]?.split('+')[0]?.trim();
    if (!base) return 'Usuario';
    return base;
  }

  logout(): void {
    this.auth.logout().subscribe(() => this.router.navigate(['/login']));
  }
}
