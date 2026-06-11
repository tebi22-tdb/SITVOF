import { validarArchivoPdf } from '../../core/archivo-pdf';
import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { HeaderComponent } from '../../layout/header/header.component';
import { NuevoEgresadoComponent, NuevoProcesoVencidoPayload } from './nuevo-egresado/nuevo-egresado.component';
import { EgresadoForm } from '../../core/datos';
import { EgresadoService, EgresadoItem, EgresadoDetail, EgresadoCrearResponse } from '../../services/egresado.service';
import { AuthService, CrearUsuarioBody, UsuarioStaffItem } from '../../services/auth.service';
import { PERFILES_ESTATICOS, PerfilCreacionUsuarioItem, perfilesDesdeDepartamentos, datosRolDesdePerfil } from '../../core/perfiles-usuario-staff';
import { CatalogoService } from '../../services/catalogo.service';

interface CrearUsuarioStaffForm extends CrearUsuarioBody {
  /** Clave del desplegable (departamento / división). */
  perfil: string;
  segmento_academico: string;
  carreras_asignadas: string[];
}

/**
 * Personal de titulación: alta/edición de egresados.
 * Coordinador / división administrativa: además administración de usuarios staff.
 * El seguimiento del proceso está en `/home/seguimiento-proceso`.
 */
@Component({
  selector: 'app-home',
  standalone: true,
  imports: [CommonModule, FormsModule, HeaderComponent, NuevoEgresadoComponent],
  templateUrl: './home.component.html',
  styleUrl: './home.component.css',
})
export class HomeComponent implements OnInit {
  mostrarFormulario = false;
  editando = false;
  guardando = false;
  mensaje = '';
  lista: EgresadoItem[] = [];
  listaUsuarios: UsuarioStaffItem[] = [];
  usuarioSeleccionado: UsuarioStaffItem | null = null;
  tabLista: 'egresados' | 'usuarios' = 'egresados';
  textoBusqueda = '';
  mostrarFiltro = false;
  fechaDesde = '';
  fechaHasta = '';
  tipoFiltroFecha = 'anexo_xxxi';
  detalle: EgresadoDetail | null = null;
  cargandoDetalle = false;
  cargandoLista = false;
  guardandoEgresado = false;
  errorLista = '';

  mostrarModalAgregarUsuario = false;
  mostrarFormularioUsuario = false;
  perfilesCreacionUsuario: PerfilCreacionUsuarioItem[] = [...PERFILES_ESTATICOS];
  usuarioForm: CrearUsuarioStaffForm = this.crearUsuarioFormInicial();
  guardandoUsuario = false;
  mensajeUsuario = '';

  /** Solo coordinador o división administrativa: agregar usuario y pestaña Usuarios. */
  get esAdminUsuariosStaff(): boolean {
    return this.authService.puedeAdministrarUsuariosStaff();
  }

  constructor(
    private egresadoService: EgresadoService,
    private authService: AuthService,
    private router: Router,
    readonly catalogoService: CatalogoService,
  ) {}

  ngOnInit(): void {
    if (this.tabLista === 'usuarios' && !this.authService.puedeAdministrarUsuariosStaff()) {
      this.tabLista = 'egresados';
    }
    this.catalogoService.departamentos$.subscribe(depts => {
      this.perfilesCreacionUsuario = [...PERFILES_ESTATICOS, ...perfilesDesdeDepartamentos(depts)];
    });
    this.cargarLista();
  }

  cargarLista(): void {
    this.errorLista = '';
    this.cargandoLista = true;
    // Vista global de alta: todas las modalidades (residencia incluida). Sin esto el API usa
    // aplicar_scope_departamento=true por defecto y la bandeja de coordinación excluye residencia.
    const filtros: {
      numero_control?: string;
      fecha_desde?: string;
      fecha_hasta?: string;
      tipo_filtro?: string;
      aplicar_scope_departamento: boolean;
    } = { aplicar_scope_departamento: false };
    if (this.textoBusqueda?.trim()) filtros.numero_control = this.textoBusqueda;
    if (this.fechaDesde) filtros.fecha_desde = this.fechaDesde;
    if (this.fechaHasta) filtros.fecha_hasta = this.fechaHasta;
    if ((this.fechaDesde || this.fechaHasta) && this.tipoFiltroFecha) filtros.tipo_filtro = this.tipoFiltroFecha;
    this.egresadoService.listar(filtros).subscribe({
      next: (lista) => {
        this.lista = lista;
        this.cargandoLista = false;
      },
      error: () => {
        this.lista = [];
        this.cargandoLista = false;
        this.errorLista = 'No se pudo cargar la lista. ¿Está el backend en ejecución?';
      },
    });
  }

  onBuscar(): void {
    if (this.tabLista === 'usuarios') {
      this.cargarListaUsuarios();
    } else {
      this.cargarLista();
    }
  }

  onSeleccionar(item: EgresadoItem): void {
    this.mensaje = '';
    this.detalle = null;
    this.cargandoDetalle = true;
    this.egresadoService.obtenerPorId(item.id).subscribe({
      next: (d) => {
        this.detalle = d;
        this.cargandoDetalle = false;
      },
      error: (err) => {
        if (err?.status === 404 && item.numero_control) {
          this.egresadoService.obtenerPorNumeroControl(item.numero_control).subscribe({
            next: (d) => {
              this.detalle = d;
              this.cargandoDetalle = false;
            },
            error: () => {
              this.detalle = null;
              this.cargandoDetalle = false;
              this.mensaje = 'Egresado no encontrado.';
            },
          });
        } else {
          this.detalle = null;
          this.cargandoDetalle = false;
          this.mensaje = err?.status === 404 ? 'Egresado no encontrado.' : 'No se pudo cargar el egresado.';
        }
      },
    });
  }

  onSeleccionarUsuario(u: UsuarioStaffItem): void {
    this.usuarioSeleccionado = u;
    this.detalle = null;
    this.mostrarFormulario = false;
    this.editando = false;
    this.mostrarFormularioUsuario = false;
    this.mensaje = '';
  }

  cambiarTabLista(tab: 'egresados' | 'usuarios'): void {
    if (tab === 'usuarios' && !this.authService.puedeAdministrarUsuariosStaff()) {
      return;
    }
    this.tabLista = tab;
    this.detalle = null;
    this.usuarioSeleccionado = null;
    this.textoBusqueda = '';
    this.errorLista = '';
    this.mostrarFormulario = false;
    this.editando = false;
    this.mostrarFormularioUsuario = false;
    if (tab === 'usuarios') this.cargarListaUsuarios();
    else this.cargarLista();
  }

  onCambioVista(valor: string): void {
    if (valor === 'egresados' || valor === 'usuarios') {
      this.cambiarTabLista(valor);
    }
  }

  cerrarDetalle(): void {
    this.detalle = null;
    this.textoBusqueda = '';
    this.cargarLista();
  }

  esResidencia(modalidad: string): boolean {
    return this.catalogoService.esResidencia(modalidad);
  }

  // ── Nuevo proceso para egresado vencido ──────────────────────────────────
  mostrarFormNuevoProceso = false;
  nuevaModalidad = '';
  nuevoNombreProyecto = '';
  nuevoAsesorInterno = '';
  nuevoAsesorExterno = '';
  nuevoDirector = '';
  nuevoAsesor1 = '';
  nuevoAsesor2 = '';
  archivoNuevoProceso: File | null = null;
  guardandoNuevoProceso = false;
  errorNuevoProceso = '';

  abrirFormNuevoProceso(): void {
    this.mostrarFormNuevoProceso = true;
    this.nuevaModalidad = '';
    this.nuevoNombreProyecto = '';
    this.nuevoAsesorInterno = '';
    this.nuevoAsesorExterno = '';
    this.nuevoDirector = '';
    this.nuevoAsesor1 = '';
    this.nuevoAsesor2 = '';
    this.archivoNuevoProceso = null;
    this.errorNuevoProceso = '';
  }

  cancelarFormNuevoProceso(): void {
    this.mostrarFormNuevoProceso = false;
    this.errorNuevoProceso = '';
  }

  onArchivoNuevoProceso(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0] ?? null;
    if (!file) {
      this.archivoNuevoProceso = null;
      return;
    }
    const err = validarArchivoPdf(file);
    if (err) {
      input.value = '';
      this.archivoNuevoProceso = null;
      this.errorNuevoProceso = err;
      return;
    }
    this.errorNuevoProceso = '';
    this.archivoNuevoProceso = file;
  }

  confirmarNuevoProceso(): void {
    if (!this.detalle || !this.nuevaModalidad.trim() || !this.nuevoNombreProyecto.trim()) return;
    const p = this.detalle.datos_personales;
    const datos: EgresadoForm = {
      numero_control: this.detalle.numero_control,
      nombre: p.nombre,
      apellido_paterno: p.apellido_paterno,
      apellido_materno: p.apellido_materno,
      carrera: p.carrera,
      nivel: p.nivel,
      direccion: p.direccion || '',
      telefono: p.telefono || '',
      correo_electronico: p.correo_electronico || '',
      nombre_proyecto: this.nuevoNombreProyecto,
      modalidad: this.nuevaModalidad,
      curso_titulacion: 'no',
      asesor_interno: this.esResidencia(this.nuevaModalidad) ? this.nuevoAsesorInterno : '',
      asesor_externo: this.esResidencia(this.nuevaModalidad) ? this.nuevoAsesorExterno : '',
      director: !this.esResidencia(this.nuevaModalidad) ? this.nuevoDirector : '',
      asesor_1: !this.esResidencia(this.nuevaModalidad) ? this.nuevoAsesor1 : '',
      asesor_2: !this.esResidencia(this.nuevaModalidad) ? this.nuevoAsesor2 : '',
      fecha_registro_anexo: '',
      fecha_expedicion_constancia: '',
      observaciones: '',
    };
    this.guardandoNuevoProceso = true;
    this.errorNuevoProceso = '';
    this.egresadoService.activarNuevoProceso(this.detalle.id, datos, this.archivoNuevoProceso).subscribe({
      next: () => {
        this.guardandoNuevoProceso = false;
        this.mostrarFormNuevoProceso = false;
        this.cargarLista();
        this.egresadoService.obtenerPorId(this.detalle!.id).subscribe(d => this.detalle = d);
      },
      error: (err) => {
        this.guardandoNuevoProceso = false;
        this.errorNuevoProceso = err?.error?.error || 'No se pudo activar el nuevo proceso.';
      },
    });
  }

  volverInicio(): void {
    this.router.navigate(['/home']);
  }

  onAgregar(payload: { datos: EgresadoForm; archivo: File | null }): void {
    if (this.guardandoEgresado) return;
    this.mensaje = '';
    this.detalle = null;
    this.guardandoEgresado = true;
    this.egresadoService.crear(payload.datos, payload.archivo).subscribe({
      next: (res: EgresadoCrearResponse) => {
        this.guardandoEgresado = false;
        this.mostrarFormulario = false;
        this.cargarLista();
        if (res.credenciales_enviadas_correo === true) {
          this.mensaje = 'Egresado registrado. Se enviaron usuario y contraseña al correo del egresado.';
        } else if (res.aviso_credenciales) {
          this.mensaje = `Egresado registrado. ${res.aviso_credenciales}`;
        } else {
          this.mensaje = 'Egresado registrado correctamente.';
        }
      },
      error: (err) => {
        this.guardandoEgresado = false;
        const body = err?.error as { error?: string; aviso_credenciales?: string } | undefined;
        const msg = body?.error ?? body?.aviso_credenciales ?? err?.message ?? err?.statusText;
        this.mensaje = msg
          ? `Error al guardar: ${msg}`
          : 'Error al guardar. Revisa que el backend esté en marcha y MongoDB conectada.';
      },
    });
  }

  onNuevoProcesoVencido(payload: NuevoProcesoVencidoPayload): void {
    if (this.guardandoEgresado) return;
    this.mensaje = '';
    this.guardandoEgresado = true;
    this.egresadoService.activarNuevoProceso(payload.id, payload.datos, payload.archivo).subscribe({
      next: () => {
        this.guardandoEgresado = false;
        this.mostrarFormulario = false;
        this.cargarLista();
        this.mensaje = 'Nuevo proceso de titulación registrado correctamente.';
      },
      error: (err) => {
        this.guardandoEgresado = false;
        const msg = err?.error?.error ?? err?.message ?? err?.statusText;
        this.mensaje = msg ? `Error al registrar nuevo proceso: ${msg}` : 'Error al registrar el nuevo proceso.';
      },
    });
  }

  onEliminar(): void {
    if (!this.detalle) return;
    if (
      !confirm(
        `¿Eliminar el registro de ${this.detalle.datos_personales.nombre} ${this.detalle.datos_personales.apellido_paterno}? Esta acción no se puede deshacer.`,
      )
    ) {
      return;
    }
    this.mensaje = '';
    this.egresadoService.eliminar(this.detalle.id).subscribe({
      next: () => {
        this.detalle = null;
        this.textoBusqueda = '';
        this.cargarLista();
        this.mensaje = 'Registro eliminado correctamente.';
      },
      error: (err) => {
        const msg = err?.error?.error ?? err?.message ?? err?.statusText;
        this.mensaje = msg ? `Error al eliminar: ${msg}` : 'Error al eliminar.';
      },
    });
  }

  onActualizar(payload: { id: string; datos: EgresadoForm; archivo: File | null }): void {
    if (this.guardandoEgresado) return;
    this.mensaje = '';
    this.guardandoEgresado = true;
    this.egresadoService.actualizar(payload.id, payload.datos, payload.archivo).subscribe({
      next: () => {
        this.guardandoEgresado = false;
        this.editando = false;
        this.egresadoService.obtenerPorId(payload.id).subscribe({
          next: (d) => {
            this.detalle = d;
          },
        });
        this.cargarLista();
        this.mensaje = 'Egresado actualizado correctamente.';
      },
      error: (err) => {
        this.guardandoEgresado = false;
        const msg = err?.error?.error ?? err?.message ?? err?.statusText;
        this.mensaje = msg
          ? `Error al actualizar: ${msg}`
          : 'Error al actualizar. Revisa que el backend esté en marcha y MongoDB conectada.';
      },
    });
  }

  abrirModalAgregarUsuario(): void {
    if (!this.authService.puedeAdministrarUsuariosStaff()) {
      return;
    }
    this.mostrarFormulario = false;
    this.editando = false;
    this.usuarioForm = this.crearUsuarioFormInicial();
    this.mensajeUsuario = '';
    this.mostrarFormularioUsuario = true;
    this.tabLista = 'usuarios';
    this.usuarioSeleccionado = null;
  }

  cerrarModalAgregarUsuario(): void {
    this.mostrarFormularioUsuario = false;
    this.mensajeUsuario = '';
  }

  guardarUsuario(): void {
    if (!this.authService.puedeAdministrarUsuariosStaff()) {
      this.mensajeUsuario = 'No tienes permiso para crear usuarios.';
      return;
    }
    this.mensajeUsuario = '';
    if (!this.usuarioForm.correo_electronico?.trim()) {
      this.mensajeUsuario = 'El correo electrónico es obligatorio.';
      return;
    }
    if (
      this.usuarioForm.rol === 'academico' &&
      this.usuarioForm.perfil !== 'academico_general' &&
      !this.usuarioForm.carreras_asignadas.length
    ) {
      this.mensajeUsuario = 'El perfil académico elegido no tiene carreras asignadas; vuelve a seleccionar el departamento.';
      return;
    }
    this.guardandoUsuario = true;
    const body: CrearUsuarioBody = {
      nombre: this.usuarioForm.nombre,
      rol: this.usuarioForm.rol,
      correo_electronico: this.usuarioForm.correo_electronico,
    };
    if (this.usuarioForm.rol === 'academico' && this.usuarioForm.perfil !== 'academico_general') {
      body.segmento_academico = this.usuarioForm.segmento_academico;
      body.carreras_asignadas = [...this.usuarioForm.carreras_asignadas];
    }
    this.authService.crearUsuario(body).subscribe({
      next: (res) => {
        this.guardandoUsuario = false;
        this.mostrarFormularioUsuario = false;
        let m = res?.message ?? 'Usuario creado.';
        if (res?.correo_enviado === false && res?.detalle_correo) {
          m += ` (${res.detalle_correo})`;
        }
        this.mensaje = m;
        this.cargarListaUsuarios();
      },
      error: (err) => {
        this.guardandoUsuario = false;
        const msg = err?.error?.error ?? err?.message ?? err?.statusText;
        this.mensajeUsuario = msg ?? 'Error al crear el usuario.';
      },
    });
  }

  onCambioPerfilUsuario(): void {
    const d = datosRolDesdePerfil(this.usuarioForm.perfil);
    this.usuarioForm.rol = d.rol;
    this.usuarioForm.segmento_academico = d.segmento_academico;
    // Las carreras vienen del catálogo dinámico; si el slug no existe usa el fallback de datosRolDesdePerfil.
    this.usuarioForm.carreras_asignadas = d.segmento_academico
      ? this.catalogoService.carrerasPorSlugSync(d.segmento_academico)
      : d.carreras_asignadas;
  }

  get carrerasAsignadasTexto(): string {
    if (!this.usuarioForm.carreras_asignadas.length) return 'Sin carreras asignadas';
    return this.usuarioForm.carreras_asignadas.join(', ');
  }

  private crearUsuarioFormInicial(): CrearUsuarioStaffForm {
    const d = datosRolDesdePerfil('division_estudios_prof_admin');
    return {
      nombre: '',
      perfil: 'division_estudios_prof_admin',
      rol: d.rol,
      correo_electronico: '',
      segmento_academico: d.segmento_academico,
      carreras_asignadas: d.carreras_asignadas,
    };
  }

  private cargarListaUsuarios(): void {
    if (!this.authService.puedeAdministrarUsuariosStaff()) {
      this.listaUsuarios = [];
      return;
    }
    this.errorLista = '';
    this.cargandoLista = true;
    this.authService.listarUsuarios().subscribe({
      next: (lista) => {
        const term = this.textoBusqueda.trim().toLowerCase();
        this.listaUsuarios = term
          ? lista.filter((u) =>
              [u.nombre, u.username, u.correo_electronico, u.curp]
                .filter(Boolean)
                .some((v) => String(v).toLowerCase().includes(term)),
            )
          : lista;
        this.cargandoLista = false;
      },
      error: () => {
        this.listaUsuarios = [];
        this.cargandoLista = false;
        this.errorLista = 'No se pudo cargar la lista de usuarios.';
      },
    });
  }
}
