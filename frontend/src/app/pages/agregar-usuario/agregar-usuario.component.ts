import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { HeaderComponent } from '../../layout/header/header.component';
import { AuthService, CrearUsuarioBody } from '../../services/auth.service';
import {
  PERFILES_ESTATICOS,
  PerfilCreacionUsuarioItem,
  perfilesDesdeDepartamentos,
  datosRolDesdePerfil,
} from '../../core/perfiles-usuario-staff';
import { CatalogoService } from '../../services/catalogo.service';

interface CrearUsuarioStaffForm extends CrearUsuarioBody {
  perfil: string;
  segmento_academico: string;
  carreras_asignadas: string[];
}

@Component({
  selector: 'app-agregar-usuario',
  standalone: true,
  imports: [CommonModule, FormsModule, HeaderComponent],
  templateUrl: './agregar-usuario.component.html',
  styleUrl: './agregar-usuario.component.css',
})
export class AgregarUsuarioComponent implements OnInit {
  perfilesCreacionUsuario: PerfilCreacionUsuarioItem[] = [...PERFILES_ESTATICOS];
  form: CrearUsuarioStaffForm = this.formInicial();
  guardando = false;
  mensajeError = '';
  mensajeOk = '';
  credenciales: { username: string; password: string } | null = null;

  get perfilesDisponibles(): PerfilCreacionUsuarioItem[] {
    return this.perfilesCreacionUsuario;
  }

  get carrerasTexto(): string {
    if (!this.form.carreras_asignadas.length) return 'Sin carreras asignadas';
    return this.form.carreras_asignadas.join(', ');
  }

  get muestraCarreras(): boolean {
    return this.form.rol === 'academico' && this.form.carreras_asignadas.length > 0;
  }

  constructor(
    private auth: AuthService,
    private catalogo: CatalogoService,
    private router: Router,
  ) {}

  ngOnInit(): void {
    if (!this.auth.puedeAdministrarUsuariosStaff()) {
      this.router.navigate(['/home']);
      return;
    }
    this.catalogo.departamentos$.subscribe((depts) => {
      this.perfilesCreacionUsuario = [...PERFILES_ESTATICOS, ...perfilesDesdeDepartamentos(depts)];
    });
  }

  volver(): void {
    this.router.navigate(['/home']);
  }

  onCambioPerfil(): void {
    const d = datosRolDesdePerfil(this.form.perfil);
    this.form.rol = d.rol;
    this.form.segmento_academico = d.segmento_academico;
    this.form.carreras_asignadas = d.segmento_academico
      ? this.catalogo.carrerasPorSlugSync(d.segmento_academico)
      : d.carreras_asignadas;
  }

  limpiar(): void {
    this.form = this.formInicial();
    this.mensajeError = '';
    this.mensajeOk = '';
    this.credenciales = null;
  }

  guardar(): void {
    this.mensajeError = '';
    this.mensajeOk = '';
    this.credenciales = null;
    if (!this.auth.puedeAdministrarUsuariosStaff()) {
      this.mensajeError = 'No tienes permiso para crear usuarios.';
      return;
    }
    if (!this.form.nombre?.trim()) {
      this.mensajeError = 'El nombre del usuario es obligatorio.';
      return;
    }
    if (!this.form.correo_electronico?.trim()) {
      this.mensajeError = 'El correo electrónico es obligatorio.';
      return;
    }
    if (
      this.form.rol === 'academico' &&
      this.form.perfil !== 'academico_general' &&
      !this.form.carreras_asignadas.length
    ) {
      this.mensajeError =
        'El perfil académico elegido no tiene carreras asignadas; vuelve a seleccionar el departamento.';
      return;
    }

    this.guardando = true;
    const body: CrearUsuarioBody = {
      nombre: this.form.nombre.trim(),
      rol: this.form.rol,
      correo_electronico: this.form.correo_electronico.trim(),
    };
    if (this.form.rol === 'academico' && this.form.perfil !== 'academico_general') {
      body.segmento_academico = this.form.segmento_academico;
      body.carreras_asignadas = [...this.form.carreras_asignadas];
    }

    this.auth.crearUsuario(body).subscribe({
      next: (res) => {
        this.guardando = false;
        let m = res?.message ?? 'Usuario creado correctamente.';
        if (res?.correo_enviado === false && res?.detalle_correo) {
          m += ` (${res.detalle_correo})`;
        }
        this.mensajeOk = m;
        if (res?.password_temporal && res?.username) {
          this.credenciales = { username: res.username, password: res.password_temporal };
        }
        this.form = this.formInicial();
      },
      error: (err) => {
        this.guardando = false;
        this.mensajeError =
          err?.error?.error ?? err?.message ?? err?.statusText ?? 'Error al crear el usuario.';
      },
    });
  }

  private formInicial(): CrearUsuarioStaffForm {
    const d = datosRolDesdePerfil('administrador');
    return {
      nombre: '',
      perfil: 'administrador',
      rol: d.rol,
      correo_electronico: '',
      segmento_academico: d.segmento_academico,
      carreras_asignadas: d.carreras_asignadas,
    };
  }
}
