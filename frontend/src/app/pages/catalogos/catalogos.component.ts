import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { CdkDragDrop, DragDropModule, moveItemInArray } from '@angular/cdk/drag-drop';
import { Router } from '@angular/router';
import { HeaderComponent } from '../../layout/header/header.component';
import { CatalogoService, CatalogoItem, CatalogoCreateRequest, PasoFlujo, PlazoAsignado } from '../../services/catalogo.service';
import { textoErrorUsuario } from '../../core/mensaje-conexion';

type TabTipo = 'carrera' | 'nivel' | 'modalidad' | 'departamento' | 'opcion';

interface FormState {
  visible: boolean;
  modo: 'crear' | 'editar';
  editandoId: string | null;
  nombreAnterior: string;
  nombre: string;
  mesesVigencia: number | null;
  definirPlazoProyecto: boolean;
  mesesProyecto: number | null;
  definirPlazoTitulacion: boolean;
  mesesTitulacion: number | null;
  /** Si el registro viejo tiene una duración que este formulario no puede editar, se conserva al guardar. */
  conservarVigencia: boolean;
  esResidencia: boolean;
  esCursoTitulacion: boolean;
  tipoMentores: string;
  slug: string;
  carrerasSeleccionadas: string[];
  departamentoId: string;
  pasoWizard: number;
  tipoUso: string;
  origenPlazo: string;
  origenFlujo: string;
  esCeneval: boolean;
  pasos: PasoFlujo[];
  plazos: PlazoAsignado[];
  nuevoPaso: PasoFlujo;
  mentoresExtra: string[];
  mentorNuevo: string;
}

@Component({
  selector: 'app-catalogos',
  standalone: true,
  imports: [CommonModule, FormsModule, DragDropModule, HeaderComponent],
  templateUrl: './catalogos.component.html',
  styleUrl: './catalogos.component.css',
})
export class CatalogosComponent implements OnInit {
  tabActivo: TabTipo = 'carrera';
  items: CatalogoItem[] = [];
  departamentosItems: CatalogoItem[] = [];
  opcionesItems: CatalogoItem[] = [];
  cargando = false;
  guardando = false;
  mensaje = '';
  mensajeError = false;

  form: FormState = this.formInicial();

  readonly actores = ['Egresado', 'Coordinación de titulación', 'Departamento académico', 'División de estudios profesionales', 'Servicios escolares'];
  readonly anexos = ['Ninguno', 'Anexo XXXI', 'Anexo XXXII', 'Anexo XXXIII', 'Anexo 9.1', 'Anexo 9.2', 'Anexo 9.3'];
  readonly accionesPaso = [
    'Enviar',
    'Confirmar',
    'Confirmar (hasta que avance el proceso)',
    'Generar anexo',
    'Solicitar',
    'Agendar',
    'Subir documento',
    'Ninguna',
  ];
  readonly pestanasDepartamento = ['Residencia', 'Anteproyecto', 'Liberación de producto', 'Sinodales'];
  tipoNuevo = '';
  pestanaNueva = '';
  editandoPaso: number | null = null;
  pasoEdicion: PasoFlujo = this.pasoVacio();

  readonly tabs: { id: TabTipo; label: string }[] = [
    { id: 'carrera',      label: 'Carreras' },
    { id: 'nivel',        label: 'Niveles' },
    { id: 'modalidad',    label: 'Modalidades' },
    { id: 'opcion',       label: 'Opciones de titulación' },
    { id: 'departamento', label: 'Departamentos' },
  ];

  get itemsActivos(): CatalogoItem[] {
    return this.items.filter(i => i.activo);
  }

  get itemsInactivos(): CatalogoItem[] {
    return this.items.filter(i => !i.activo);
  }

  get esTabDepartamento(): boolean { return this.tabActivo === 'departamento'; }
  get esTabModalidad(): boolean    { return this.tabActivo === 'modalidad'; }
  get esTabCarrera(): boolean      { return this.tabActivo === 'carrera'; }
  get esTabOpcion(): boolean       { return this.tabActivo === 'opcion'; }
  get esFlujoNuevo(): boolean      { return this.form.origenFlujo === 'personalizado'; }
  get totalPasosWizard(): number   { return this.esFlujoNuevo ? 5 : 3; }
  get esUltimoPaso(): boolean      { return this.esTabModalidad && this.form.pasoWizard === this.totalPasosWizard; }

  /** Carreras activas para los checkboxes del formulario de departamento. */
  get carrerasDisponibles(): string[] {
    return this.items
      .filter(i => i.tipo === 'carrera' && i.activo)
      .map(i => i.nombre);
  }

  constructor(
    private catalogoService: CatalogoService,
    private router: Router,
  ) {}

  ngOnInit(): void {
    this.cargar();
  }

  cambiarTab(tab: TabTipo): void {
    if (tab === this.tabActivo) return;
    this.tabActivo = tab;
    this.tipoNuevo = '';
    this.form = this.formInicial();
    this.editandoPaso = null;
    this.mensaje = '';
    this.cargar();
  }

  cargar(): void {
    this.cargando = true;
    this.catalogoService.listarTodos().subscribe({
      next: todos => {
        this.departamentosItems = todos
          .filter(i => i.tipo === 'departamento' && i.activo)
          .sort((a, b) => a.nombre.localeCompare(b.nombre));

        this.opcionesItems = todos
          .filter(i => i.tipo === 'opcion')
          .sort((a, b) => a.orden - b.orden || a.nombre.localeCompare(b.nombre));

        this.items = todos
          .filter(i => i.tipo === this.tabActivo)
          .sort((a, b) => a.orden - b.orden || a.nombre.localeCompare(b.nombre));

        this.cargando = false;
      },
      error: (err) => {
        this.items = [];
        this.cargando = false;
        this.mostrarMensaje(textoErrorUsuario(err, 'No se pudo cargar la lista.'), true);
      },
    });
  }

  abrirFormularioCrear(): void {
    this.tipoNuevo = '';
    this.form = this.formInicial();
    this.form.visible = true;
    this.form.modo = 'crear';
    this.mensaje = '';
  }

  abrirFormularioEditar(item: CatalogoItem): void {
    let departamentoId = '';
    if (this.esTabCarrera) {
      const dept = this.departamentosItems.find(d =>
        d.carreras.some(c => this.catalogoService.normalizarCarreraKey(c) === this.catalogoService.normalizarCarreraKey(item.nombre)),
      );
      departamentoId = dept?.id ?? '';
    }

    this.tipoNuevo = '';
    const mesesProyecto = this.plazoProyectoDe(item);
    const mesesTitulacion = this.plazoTitulacionDe(item);
    this.form = {
      visible: true,
      modo: 'editar',
      editandoId: item.id,
      nombreAnterior: item.nombre,
      nombre: item.nombre,
      mesesVigencia: item.mesesVigencia ?? null,
      definirPlazoProyecto: mesesProyecto != null,
      mesesProyecto,
      definirPlazoTitulacion: mesesTitulacion != null,
      mesesTitulacion,
      conservarVigencia: mesesProyecto == null && mesesTitulacion == null && item.mesesVigencia != null,
      esResidencia: item.esResidencia,
      pasoWizard: 1,
      tipoUso: this.tipoAlAbrir(item),
      origenPlazo: item.origenPlazo || this.inferirOrigen(item),
      origenFlujo: item.origenFlujo || this.inferirOrigen(item),
      esCeneval: !!item.esCeneval,
      pasos: item.pasos?.length ? item.pasos.map(p => ({ ...p })) : this.pasosDe(item.origenFlujo || this.inferirOrigen(item)),
      plazos: (item.plazos ?? []).map(p => ({ ...p })),
      nuevoPaso: this.pasoVacio(),
      mentoresExtra: [...(item.mentoresExtra ?? [])],
      mentorNuevo: '',
      esCursoTitulacion: item.esCursoTitulacion ?? false,
      tipoMentores: item.tipoMentores ?? (item.esResidencia ? 'residencia' : 'estandar'),
      slug: item.slug ?? '',
      carrerasSeleccionadas: [...item.carreras],
      departamentoId,
    };
    this.mensaje = '';
  }

  cancelarFormulario(): void {
    this.tipoNuevo = '';
    this.form = this.formInicial();
    this.editandoPaso = null;
    this.mensaje = '';
  }

  guardar(): void {
    if (!this.form.nombre.trim()) {
      this.mostrarMensaje('El nombre es obligatorio.', true);
      return;
    }
    if (this.esTabCarrera && !this.form.departamentoId) {
      this.mostrarMensaje('Debes seleccionar el departamento al que pertenece la carrera.', true);
      return;
    }
    if (this.esTabDepartamento && !this.form.slug.trim()) {
      this.mostrarMensaje('La clave interna (slug) es obligatoria para departamentos.', true);
      return;
    }
    if (this.esTabModalidad && this.form.origenFlujo === 'personalizado') {
      if (this.editandoPaso !== null && !this.guardarEdicion()) return;
      this.aplicarPlazosDesdePasos();
    }
    const mesesProyecto = this.esTabModalidad
      ? this.normalizarPlazo(this.form.mesesProyecto, this.form.definirPlazoProyecto)
      : null;
    const mesesTitulacion = this.esTabModalidad
      ? this.normalizarPlazo(this.form.mesesTitulacion, this.form.definirPlazoTitulacion)
      : null;
    const tipoUso = this.tipoUsoResuelto();
    if (this.esTabModalidad && !tipoUso) {
      this.mostrarMensaje('Elige el tipo.', true);
      return;
    }
    if (this.esTabModalidad && !this.form.origenPlazo) {
      this.mostrarMensaje('Elige cómo se definen los plazos.', true);
      return;
    }
    if (this.esTabModalidad && !this.form.origenFlujo) {
      this.mostrarMensaje('Elige el flujo de la modalidad.', true);
      return;
    }
    if (this.esTabModalidad && this.form.origenFlujo === 'personalizado' && !this.form.pasos.length) {
      this.mostrarMensaje('Agrega al menos un paso al flujo.', true);
      return;
    }
    if (this.esTabModalidad && this.esFlujoNuevo && !this.plazosValidos()) return;
    if (this.esTabModalidad && (mesesProyecto === 'invalido' || mesesTitulacion === 'invalido')) {
      this.mostrarMensaje('Si defines un plazo, escribe un número entero de 1 a 12.', true);
      return;
    }

    // Calcular orden automático: al final de los ítems activos existentes.
    const ordenAuto = this.form.modo === 'crear'
      ? (this.itemsActivos.length > 0 ? Math.max(...this.itemsActivos.map(i => i.orden)) + 1 : 0)
      : (this.items.find(i => i.id === this.form.editandoId)?.orden ?? 0);

    const proyecto = mesesProyecto === 'invalido' ? null : mesesProyecto;
    const titulacion = mesesTitulacion === 'invalido' ? null : mesesTitulacion;
    const request: CatalogoCreateRequest = {
      tipo: this.tabActivo,
      nombre: this.form.nombre.trim(),
      orden: ordenAuto,
      mesesVigencia: this.esTabModalidad ? this.vigenciaAEnviar(proyecto, titulacion) : undefined,
      mesesProyecto: this.esTabModalidad ? proyecto : undefined,
      mesesTitulacion: this.esTabModalidad ? titulacion : undefined,
      esResidencia: this.esTabModalidad ? this.form.esResidencia : undefined,
      esCursoTitulacion: this.esTabModalidad ? (this.form.tipoUso === 'curso' || this.form.esCursoTitulacion) : undefined,
      tipoMentores: this.esTabModalidad ? this.form.tipoMentores : undefined,
      tipoUso: this.esTabModalidad ? tipoUso ?? undefined : undefined,
      origenPlazo: this.esTabModalidad ? this.form.origenPlazo : undefined,
      origenFlujo: this.esTabModalidad ? this.form.origenFlujo : undefined,
      esCeneval: this.esTabModalidad ? this.form.esCeneval || this.form.origenFlujo === 'ceneval' || this.form.origenPlazo === 'ceneval' : undefined,
      pasos: this.esTabModalidad ? this.form.pasos : undefined,
      plazos: this.esTabModalidad && this.esFlujoNuevo ? this.form.plazos : undefined,
      mentoresExtra: this.esTabModalidad ? this.form.mentoresExtra : undefined,
      slug: this.esTabDepartamento ? this.form.slug.trim() : undefined,
      carreras: this.esTabDepartamento ? this.form.carrerasSeleccionadas : undefined,
    };

    this.guardando = true;
    this.mensaje = '';

    if (this.esTabCarrera) {
      this.guardarCarrera(request);
    } else if (this.form.modo === 'crear') {
      this.catalogoService.crearItem(request).subscribe({
        next: () => this.finalizarGuardado('Elemento creado.'),
        error: err => this.finalizarGuardado(textoErrorUsuario(err, 'No se pudo crear.'), true),
      });
    } else {
      this.catalogoService.actualizarItem(this.form.editandoId!, request).subscribe({
        next: () => this.finalizarGuardado('Elemento actualizado.'),
        error: err => this.finalizarGuardado(textoErrorUsuario(err, 'No se pudo actualizar.'), true),
      });
    }
  }

  desactivar(item: CatalogoItem): void {
    if (!confirm(`¿Desactivar "${item.nombre}"? No aparecerá en nuevos registros pero los expedientes existentes no se ven afectados.`)) return;
    this.catalogoService.desactivarItem(item.id).subscribe({
      next: () => this.finalizarGuardado('Elemento desactivado.'),
      error: () => this.mostrarMensaje('No se pudo desactivar.', true),
    });
  }

  activar(item: CatalogoItem): void {
    this.catalogoService.activarItem(item.id).subscribe({
      next: () => this.finalizarGuardado('Elemento activado.'),
      error: () => this.mostrarMensaje('No se pudo activar.', true),
    });
  }

  eliminar(item: CatalogoItem): void {
    if (item.activo) return;
    if (!confirm(`¿Eliminar "${item.nombre}"? Esta acción no se puede deshacer.`)) return;
    this.catalogoService.eliminarItem(item.id).subscribe({
      next: () => this.finalizarGuardado(this.esTabOpcion ? 'Opción eliminada.' : 'Modalidad eliminada.'),
      error: err => this.mostrarMensaje(textoErrorUsuario(err, 'No se pudo eliminar.'), true),
    });
  }

  toggleCarrera(carrera: string): void {
    const idx = this.form.carrerasSeleccionadas.indexOf(carrera);
    if (idx >= 0) this.form.carrerasSeleccionadas.splice(idx, 1);
    else this.form.carrerasSeleccionadas.push(carrera);
  }

  carreraSeleccionada(carrera: string): boolean {
    return this.form.carrerasSeleccionadas.includes(carrera);
  }

  togglePlazoProyecto(activo: boolean): void {
    this.form.definirPlazoProyecto = activo;
    if (!activo) this.form.mesesProyecto = null;
  }

  togglePlazoTitulacion(activo: boolean): void {
    this.form.definirPlazoTitulacion = activo;
    if (!activo) this.form.mesesTitulacion = null;
  }

  siguiente(): void {
    if (this.form.pasoWizard === 1) {
      if (!this.tipoUsoResuelto()) {
        this.mostrarMensaje('Elige el tipo.', true);
        return;
      }
      if (!this.form.nombre.trim()) {
        this.mostrarMensaje('El nombre es obligatorio.', true);
        return;
      }
    }
    if (this.form.pasoWizard === 2 && !this.form.origenFlujo) {
      this.mostrarMensaje('Elige un flujo existente o uno nuevo.', true);
      return;
    }
    if (this.form.pasoWizard === 3 && this.esFlujoNuevo) {
      if (this.editandoPaso !== null && !this.guardarEdicion()) return;
      if (!this.form.pasos.length) {
        this.mostrarMensaje('Agrega al menos un paso.', true);
        return;
      }
    }
    if (this.form.pasoWizard === 4 && this.esFlujoNuevo && !this.plazosValidos()) return;
    this.mensaje = '';
    this.form.pasoWizard = Math.min(this.totalPasosWizard, this.form.pasoWizard + 1);
  }

  atras(): void {
    this.mensaje = '';
    this.form.pasoWizard = Math.max(1, this.form.pasoWizard - 1);
  }

  elegirPlazo(origen: string): void {
    this.form.origenPlazo = origen;
    if (origen === 'tesis') {
      this.form.definirPlazoProyecto = true;
      this.form.mesesProyecto = 12;
      this.form.definirPlazoTitulacion = true;
      this.form.mesesTitulacion = 6;
      this.form.esResidencia = false;
      this.form.esCeneval = false;
      this.form.tipoMentores = 'estandar';
    } else if (origen === 'residencia') {
      this.form.definirPlazoProyecto = false;
      this.form.mesesProyecto = null;
      this.form.definirPlazoTitulacion = true;
      this.form.mesesTitulacion = 6;
      this.form.esResidencia = true;
      this.form.esCeneval = false;
      this.form.tipoMentores = 'residencia';
    } else if (origen === 'ceneval') {
      this.form.definirPlazoProyecto = false;
      this.form.mesesProyecto = null;
      this.form.definirPlazoTitulacion = false;
      this.form.mesesTitulacion = null;
      this.form.esResidencia = false;
      this.form.esCeneval = true;
      this.form.tipoMentores = 'ninguno';
    }
    if (!this.form.origenFlujo || this.form.origenFlujo === origen) {
      this.elegirFlujo(origen === 'personalizado' ? 'personalizado' : origen);
    }
  }

  elegirFlujoModalidad(origen: string): void {
    if (origen === 'personalizado') {
      this.form.origenPlazo = 'personalizado';
      this.form.origenFlujo = 'personalizado';
      this.form.definirPlazoProyecto = false;
      this.form.definirPlazoTitulacion = false;
      this.form.mesesProyecto = null;
      this.form.mesesTitulacion = null;
      this.form.esResidencia = false;
      this.form.esCeneval = false;
      this.form.pasos = [];
      this.form.plazos = [];
      this.editandoPaso = null;
      return;
    }
    this.form.origenFlujo = '';
    this.elegirPlazo(origen);
  }

  elegirFlujo(origen: string): void {
    this.form.origenFlujo = origen;
    if (origen === 'personalizado') {
      return;
    }
    this.form.pasos = this.pasosDe(origen);
    if (origen === 'ceneval') this.form.esCeneval = true;
    if (origen === 'residencia') this.form.esResidencia = true;
    if (origen === 'tesis') {
      this.form.esResidencia = false;
      this.form.esCeneval = false;
    }
  }

  insertarPaso(indice: number): void {
    if (this.editandoPaso !== null && !this.guardarEdicion()) return;
    const lista = [...this.form.pasos];
    const destino = Math.max(0, Math.min(indice, lista.length));
    lista.splice(destino, 0, this.pasoVacio());
    this.form.pasos = lista;
    this.abrirEdicion(destino);
  }

  editarPaso(indice: number): void {
    if (this.editandoPaso === indice) return;
    if (this.editandoPaso !== null && !this.guardarEdicion()) return;
    this.abrirEdicion(indice);
  }

  duplicarPaso(indice: number): void {
    if (this.editandoPaso !== null && !this.guardarEdicion()) return;
    const origen = this.form.pasos[indice];
    if (!origen) return;
    const copia: PasoFlujo = { ...origen, cierraProceso: false };
    const lista = [...this.form.pasos];
    lista.splice(indice + 1, 0, copia);
    this.form.pasos = lista;
    this.abrirEdicion(indice + 1);
  }

  soltarPaso(event: CdkDragDrop<PasoFlujo[]>): void {
    if (this.editandoPaso !== null) return;
    const desde = event.previousIndex;
    const hacia = event.currentIndex;
    const lista = [...this.form.pasos];
    moveItemInArray(lista, desde, hacia);
    this.form.pasos = lista;
    this.form.plazos = this.form.plazos.map(p => ({
      ...p,
      pasoInicio: this.moverIndice(p.pasoInicio, desde, hacia),
      pasoFin: this.moverIndice(p.pasoFin, desde, hacia),
    }));
  }

  guardarEdicion(): boolean {
    if (this.editandoPaso === null) return true;
    const paso = this.normalizarPaso(this.pasoEdicion);
    if (!paso) return false;
    const lista = this.form.pasos.map(p => ({ ...p }));
    if (paso.cierraProceso) {
      for (const item of lista) item.cierraProceso = false;
    }
    lista[this.editandoPaso] = paso;
    this.form.pasos = lista;
    this.editandoPaso = null;
    this.mensaje = '';
    return true;
  }

  cancelarEdicion(): void {
    if (this.editandoPaso === null) return;
    const actual = this.form.pasos[this.editandoPaso];
    const indice = this.editandoPaso;
    this.editandoPaso = null;
    if (!actual?.nombre.trim()) this.quitarPaso(indice);
    this.mensaje = '';
  }

  alCambiarAccion(): void {
    if (this.pasoEdicion.accion === 'Subir documento') this.pasoEdicion.pideArchivo = true;
  }

  detallePaso(paso: PasoFlujo): string {
    const partes = [paso.accion || 'Sin acción'];
    if (paso.anexo && paso.anexo !== 'Ninguno') partes.push(paso.anexo);
    if (paso.llevaPlazo && paso.meses) {
      partes.push(`${paso.tipoPlazo === 'titulacion' ? 'Titulación' : 'Proyecto'} ${paso.meses} meses`);
    }
    if (paso.pideArchivo) partes.push('Pide archivo');
    if (paso.permiteRegreso) partes.push('Se puede regresar');
    if (paso.cierraProceso) partes.push('Cierra el proceso');
    if (paso.pestanaDepartamento) partes.push(`Pestaña: ${paso.pestanaDepartamento}`);
    return partes.join(' · ');
  }

  agregarMentor(): void {
    const nombre = this.form.mentorNuevo.trim();
    if (!nombre) {
      this.mostrarMensaje('Escribe el nombre del mentor.', true);
      return;
    }
    if (this.form.mentoresExtra.some(m => m.toLowerCase() === nombre.toLowerCase())) {
      this.form.mentorNuevo = '';
      return;
    }
    this.form.mentoresExtra = [...this.form.mentoresExtra, nombre];
    this.form.mentorNuevo = '';
    this.mensaje = '';
  }

  quitarMentor(indice: number): void {
    this.form.mentoresExtra = this.form.mentoresExtra.filter((_, i) => i !== indice);
  }

  elegirMentores(tipo: string): void {
    this.form.tipoMentores = tipo;
    this.form.esResidencia = tipo === 'residencia';
    if (tipo !== 'ninguno') this.form.esCeneval = false;
  }

  quitarPaso(indice: number): void {
    this.form.pasos = this.form.pasos.filter((_, i) => i !== indice);
    const bajar = (n: number | null) => n == null ? null : n === indice ? null : n > indice ? n - 1 : n;
    this.form.plazos = this.form.plazos.map(p => ({ ...p, pasoInicio: bajar(p.pasoInicio), pasoFin: bajar(p.pasoFin) }));
    if (this.editandoPaso === indice) this.editandoPaso = null;
    else if (this.editandoPaso !== null && this.editandoPaso > indice) this.editandoPaso -= 1;
  }

  moverPaso(indice: number, delta: number): void {
    const destino = indice + delta;
    if (destino < 0 || destino >= this.form.pasos.length) return;
    const lista = [...this.form.pasos];
    const [paso] = lista.splice(indice, 1);
    lista.splice(destino, 0, paso);
    this.form.pasos = lista;
  }

  textoPlazos(item: CatalogoItem): string {
    const partes: string[] = [];
    const proyecto = this.enRangoMeses(item.mesesProyecto);
    const titulacion = this.enRangoMeses(item.mesesTitulacion);
    if (proyecto != null) partes.push(`Proyecto: ${proyecto} meses`);
    if (titulacion != null) partes.push(`Titulación: ${titulacion} meses`);
    if (partes.length) return partes.join(' · ');
    return item.mesesVigencia != null ? `${item.mesesVigencia} meses` : 'Sin plazo';
  }

  nombreDeptDeCarrera(nombreCarrera: string): string {
    const key = this.catalogoService.normalizarCarreraKey(nombreCarrera);
    return this.departamentosItems.find(d =>
      d.carreras.some(c => this.catalogoService.normalizarCarreraKey(c) === key)
    )?.nombre ?? '—';
  }

  volver(): void {
    this.router.navigate(['/home']);
  }

  // ── Lógica especial para carreras ───────────────────────────────────────────

  private guardarCarrera(request: CatalogoCreateRequest): void {
    const deptNuevo = this.departamentosItems.find(d => d.id === this.form.departamentoId);

    if (this.form.modo === 'crear') {
      this.catalogoService.crearItem(request).subscribe({
        next: carreraCreada => {
          if (deptNuevo) {
            this.actualizarDepartamento(deptNuevo, [...deptNuevo.carreras, carreraCreada.nombre], () =>
              this.finalizarGuardado('Carrera creada y asignada al departamento.'),
            );
          } else {
            this.finalizarGuardado('Carrera creada.');
          }
        },
        error: err => this.finalizarGuardado(textoErrorUsuario(err, 'No se pudo crear la carrera.'), true),
      });
    } else {
      const nombreAnterior = this.form.nombreAnterior;
      const keyAnterior = this.catalogoService.normalizarCarreraKey(nombreAnterior);
      const deptAnterior = this.departamentosItems.find(d =>
        d.carreras.some(c => this.catalogoService.normalizarCarreraKey(c) === keyAnterior),
      );

      this.catalogoService.actualizarItem(this.form.editandoId!, request).subscribe({
        next: carreraActualizada => {
          this.actualizarDepartamentosTrasCambioCarrera(
            nombreAnterior, carreraActualizada.nombre, deptAnterior ?? null, deptNuevo ?? null,
          );
        },
        error: err => this.finalizarGuardado(textoErrorUsuario(err, 'No se pudo actualizar la carrera.'), true),
      });
    }
  }

  private actualizarDepartamentosTrasCambioCarrera(
    nombreAnterior: string,
    nombreNuevo: string,
    deptAnterior: CatalogoItem | null,
    deptNuevo: CatalogoItem | null,
  ): void {
    const mismoDept = deptAnterior?.id === deptNuevo?.id;
    const mismoNombre = nombreAnterior.trim() === nombreNuevo.trim();

    if (mismoDept && mismoNombre) {
      // Sin cambios que afecten departamentos
      this.finalizarGuardado('Carrera actualizada.');
      return;
    }

    if (mismoDept && deptAnterior) {
      // Mismo depto, solo cambia el nombre: reemplazar en la lista
      const keyAnterior = this.catalogoService.normalizarCarreraKey(nombreAnterior);
      const nuevasCarreras = deptAnterior.carreras.map(c =>
        this.catalogoService.normalizarCarreraKey(c) === keyAnterior ? nombreNuevo : c,
      );
      this.actualizarDepartamento(deptAnterior, nuevasCarreras, () =>
        this.finalizarGuardado('Carrera actualizada.'),
      );
      return;
    }

    // Depto distinto: quitar del anterior y agregar al nuevo
    const pending = { count: (deptAnterior ? 1 : 0) + (deptNuevo ? 1 : 0) };
    const done = () => { if (--pending.count === 0) this.finalizarGuardado('Carrera actualizada y reasignada.'); };

    if (deptAnterior) {
      const keyAnterior = this.catalogoService.normalizarCarreraKey(nombreAnterior);
      const filtradas = deptAnterior.carreras.filter(c => this.catalogoService.normalizarCarreraKey(c) !== keyAnterior);
      this.actualizarDepartamento(deptAnterior, filtradas, done);
    }
    if (deptNuevo) {
      const agregadas = [...deptNuevo.carreras, nombreNuevo];
      this.actualizarDepartamento(deptNuevo, agregadas, done);
    }
    if (!deptAnterior && !deptNuevo) {
      this.finalizarGuardado('Carrera actualizada.');
    }
  }

  private actualizarDepartamento(dept: CatalogoItem, carreras: string[], onDone: () => void): void {
    this.catalogoService.actualizarItem(dept.id, {
      tipo: 'departamento',
      nombre: dept.nombre,
      slug: dept.slug ?? '',
      orden: dept.orden,
      carreras,
    }).subscribe({ next: onDone, error: onDone });
  }

  private finalizarGuardado(texto: string, esError = false): void {
    this.guardando = false;
    this.editandoPaso = null;
    this.form = this.formInicial();
    this.mostrarMensaje(texto, esError);
    this.catalogoService.refresh();
    this.cargar();
  }

  private mostrarMensaje(texto: string, esError = false): void {
    this.mensaje = texto;
    this.mensajeError = esError;
  }

  private vigenciaAEnviar(proyecto: number | null, titulacion: number | null): number | null {
    const calculada = this.form.esResidencia ? (titulacion ?? proyecto) : (proyecto ?? titulacion);
    if (calculada != null) return calculada;
    if (this.form.conservarVigencia && !this.form.definirPlazoProyecto && !this.form.definirPlazoTitulacion) {
      return this.form.mesesVigencia;
    }
    return null;
  }

  private normalizarPlazo(valor: number | null, activo: boolean): number | 'invalido' | null {
    if (!activo) return null;
    if (valor === null || valor === undefined || (valor as unknown) === '') return 'invalido';
    const n = Number(valor);
    if (!Number.isInteger(n) || n < 1 || n > 12) return 'invalido';
    return n;
  }

  private enRangoMeses(valor: number | null | undefined): number | null {
    if (valor == null) return null;
    const n = Number(valor);
    if (!Number.isInteger(n) || n < 1 || n > 12) return null;
    return n;
  }

  /** Registros anteriores solo tienen una duración: la residencia va al proceso de titulación; el resto, al proyecto. */
  private plazoProyectoDe(item: CatalogoItem): number | null {
    const propio = this.enRangoMeses(item.mesesProyecto);
    if (propio != null) return propio;
    if (item.mesesProyecto != null || item.mesesTitulacion != null || item.esResidencia) return null;
    return this.enRangoMeses(item.mesesVigencia);
  }

  private plazoTitulacionDe(item: CatalogoItem): number | null {
    const propio = this.enRangoMeses(item.mesesTitulacion);
    if (propio != null) return propio;
    if (item.mesesTitulacion != null || item.mesesProyecto != null || !item.esResidencia) return null;
    return this.enRangoMeses(item.mesesVigencia);
  }

  private inferirOrigen(item: CatalogoItem): string {
    if (item.esCeneval || item.nombre.toLowerCase().includes('ceneval')) return 'ceneval';
    if (item.esResidencia) return 'residencia';
    return 'tesis';
  }

  private pasosDe(origen: string): PasoFlujo[] {
    const base: Record<string, PasoFlujo[]> = {
      tesis: [
        { nombre: 'Registro de la solicitud', hace: 'Egresado', recibe: 'Coordinación de titulación', anexo: 'Anexo XXXI' },
        { nombre: 'Envío al departamento académico', hace: 'Coordinación de titulación', recibe: 'Departamento académico', anexo: 'Ninguno' },
        { nombre: 'Desarrollo y liberación del proyecto', hace: 'Departamento académico', recibe: 'División de estudios profesionales', anexo: 'Ninguno' },
        { nombre: 'Anexos XXXII y XXXIII', hace: 'Departamento académico', recibe: 'División de estudios profesionales', anexo: 'Anexo XXXII' },
        { nombre: 'Anexo 9.1, 9.2 y 9.3', hace: 'División de estudios profesionales', recibe: 'Egresado', anexo: 'Anexo 9.1' },
        { nombre: 'Documentación escaneada', hace: 'Egresado', recibe: 'División de estudios profesionales', anexo: 'Ninguno' },
      ],
      residencia: [
        { nombre: 'Registro de la solicitud', hace: 'Egresado', recibe: 'Coordinación de titulación', anexo: 'Anexo XXXI' },
        { nombre: 'Envío al departamento académico', hace: 'Coordinación de titulación', recibe: 'Departamento académico', anexo: 'Ninguno' },
        { nombre: 'Recepción de anexos XXXII y XXXIII', hace: 'Departamento académico', recibe: 'División de estudios profesionales', anexo: 'Anexo XXXII' },
        { nombre: 'Anexo 9.1', hace: 'División de estudios profesionales', recibe: 'Egresado', anexo: 'Anexo 9.1' },
        { nombre: 'Anexo 9.2', hace: 'Servicios escolares', recibe: 'División de estudios profesionales', anexo: 'Anexo 9.2' },
        { nombre: 'Sinodales y acto protocolario', hace: 'División de estudios profesionales', recibe: 'Departamento académico', anexo: 'Anexo 9.3' },
        { nombre: 'Documentación escaneada', hace: 'Egresado', recibe: 'División de estudios profesionales', anexo: 'Ninguno' },
      ],
      ceneval: [
        { nombre: 'Registro de la solicitud', hace: 'Egresado', recibe: 'Coordinación de titulación', anexo: 'Ninguno' },
        { nombre: 'Recepción de solicitud y testimonio', hace: 'Coordinación de titulación', recibe: 'Servicios escolares', anexo: 'Ninguno' },
        { nombre: 'Anexo 9.1', hace: 'División de estudios profesionales', recibe: 'Egresado', anexo: 'Anexo 9.1' },
        { nombre: 'Anexo 9.2', hace: 'Servicios escolares', recibe: 'División de estudios profesionales', anexo: 'Anexo 9.2' },
        { nombre: 'Sinodales y acto protocolario', hace: 'División de estudios profesionales', recibe: 'Egresado', anexo: 'Anexo 9.3' },
        { nombre: 'Documentación escaneada', hace: 'Egresado', recibe: 'División de estudios profesionales', anexo: 'Ninguno' },
      ],
    };
    return (base[origen] || []).map(p => ({ ...p }));
  }

  private aplicarPlazosDesdePasos(): void {
    const proyecto = this.form.pasos.find(p => p.llevaPlazo && p.tipoPlazo === 'proyecto');
    const titulacion = this.form.pasos.find(p => p.llevaPlazo && p.tipoPlazo === 'titulacion');
    this.form.definirPlazoProyecto = !!proyecto;
    this.form.mesesProyecto = proyecto?.meses ?? null;
    this.form.definirPlazoTitulacion = !!titulacion;
    this.form.mesesTitulacion = titulacion?.meses ?? null;
  }

  private pestanaResuelta(origen: PasoFlujo): string {
    if (origen.recibe !== 'Departamento académico') return '';
    if (origen.pestanaDepartamento === '__nueva__') return this.pestanaNueva.trim();
    return (origen.pestanaDepartamento || '').trim();
  }

  private abrirEdicion(indice: number): void {
    const p = this.form.pasos[indice];
    this.pasoEdicion = {
      nombre: p.nombre,
      hace: p.hace || 'Egresado',
      recibe: p.recibe || 'Coordinación de titulación',
      anexo: p.anexo || 'Ninguno',
      accion: p.accion || 'Enviar',
      pideArchivo: !!p.pideArchivo || p.accion === 'Subir documento',
      permiteRegreso: !!p.permiteRegreso,
      cierraProceso: !!p.cierraProceso,
      pestanaDepartamento: p.pestanaDepartamento || '',
      llevaPlazo: !!p.llevaPlazo,
      tipoPlazo: p.tipoPlazo || 'proyecto',
      meses: p.meses ?? null,
    };
    this.editandoPaso = indice;
    this.mensaje = '';
  }

  private normalizarPaso(origen: PasoFlujo): PasoFlujo | null {
    const nombre = origen.nombre.trim();
    if (!nombre) {
      this.mostrarMensaje('Escribe el nombre del paso.', true);
      return null;
    }
    const accion = origen.accion || 'Enviar';
    if (accion === 'Generar anexo' && (!origen.anexo || origen.anexo === 'Ninguno')) {
      this.mostrarMensaje('Elige el anexo que se va a generar.', true);
      return null;
    }
    const pestana = this.pestanaResuelta(origen);
    if (origen.recibe === 'Departamento académico' && !pestana) {
      this.mostrarMensaje('Elige la pestaña del departamento, o escribe una nueva.', true);
      return null;
    }
    let meses: number | null = null;
    let tipoPlazo = '';
    if (origen.llevaPlazo) {
      const n = Number(origen.meses);
      if (!Number.isInteger(n) || n < 1 || n > 12) {
        this.mostrarMensaje('El plazo del paso debe ser un número de 1 a 12.', true);
        return null;
      }
      meses = n;
      tipoPlazo = origen.tipoPlazo === 'titulacion' ? 'titulacion' : 'proyecto';
    }
    return {
      nombre,
      hace: origen.hace,
      recibe: origen.recibe,
      anexo: origen.anexo || 'Ninguno',
      accion,
      pideArchivo: accion === 'Subir documento' || !!origen.pideArchivo,
      permiteRegreso: !!origen.permiteRegreso,
      cierraProceso: !!origen.cierraProceso,
      pestanaDepartamento: this.pestanaResuelta(origen),
      llevaPlazo: !!origen.llevaPlazo,
      tipoPlazo,
      meses,
    };
  }

  private pasoVacio(): PasoFlujo {
    return {
      nombre: '',
      hace: 'Egresado',
      recibe: 'Coordinación de titulación',
      anexo: 'Ninguno',
      accion: 'Enviar',
      pideArchivo: false,
      permiteRegreso: false,
      cierraProceso: false,
      pestanaDepartamento: '',
      llevaPlazo: false,
      tipoPlazo: 'proyecto',
      meses: null,
    };
  }

  get opcionesParaTipo(): { clave: string; nombre: string }[] {
    const base = [
      { clave: 'titulacion', nombre: 'Titulación' },
      { clave: 'integral', nombre: 'Titulación integral' },
      { clave: 'curso', nombre: 'Curso de titulación' },
    ];
    const fijas = new Set(base.map(o => o.clave));
    const extras = this.opcionesItems
      .filter(i => i.activo && i.slug && !fijas.has(i.slug))
      .map(i => ({ clave: i.slug as string, nombre: i.nombre }));
    return [...base, ...extras];
  }

  private tipoAlAbrir(item: CatalogoItem): string {
    const uso = item.tipoUso?.trim() || '';
    if (uso === 'curso' || (item.esCursoTitulacion && (uso === '' || uso === 'integral'))) return 'curso';
    if (uso === 'titulacion' || uso === 'integral') return uso;
    const porSlug = this.opcionesItems.find(o => o.slug === uso);
    if (porSlug?.slug) return porSlug.slug;
    const porNombre = this.opcionesItems.find(o => o.nombre.trim().toLowerCase() === uso.toLowerCase());
    if (porNombre?.slug) return porNombre.slug;
    return uso || 'integral';
  }

  private tipoUsoResuelto(): string | null {
    const clave = this.form.tipoUso.trim();
    if (!clave) return null;
    if (clave === 'titulacion' || clave === 'integral' || clave === 'curso') return clave;
    const opcion = this.opcionesItems.find(o => o.slug === clave);
    return (opcion?.nombre || clave).slice(0, 40);
  }

  agregarPlazo(): void {
    this.form.plazos = [...this.form.plazos, {
      inicia: '',
      pasoInicio: null,
      termina: 'ninguno',
      pasoFin: null,
      meses: 6,
    }];
  }

  quitarPlazo(indice: number): void {
    this.form.plazos = this.form.plazos.filter((_, i) => i !== indice);
  }

  private plazosValidos(): boolean {
    for (const [i, plazo] of this.form.plazos.entries()) {
      const n = i + 1;
      if (!plazo.inicia) {
        this.mostrarMensaje(`El plazo ${n} necesita saber dónde inicia.`, true);
        return false;
      }
      if (plazo.inicia === 'paso' && (plazo.pasoInicio == null || plazo.pasoInicio < 0 || plazo.pasoInicio >= this.form.pasos.length)) {
        this.mostrarMensaje(`El plazo ${n} debe iniciar en un paso.`, true);
        return false;
      }
      if (plazo.termina === 'paso' && (plazo.pasoFin == null || plazo.pasoFin < 0 || plazo.pasoFin >= this.form.pasos.length)) {
        this.mostrarMensaje(`El plazo ${n} debe terminar en un paso, o en ninguno.`, true);
        return false;
      }
      const meses = Number(plazo.meses);
      if (!Number.isInteger(meses) || meses < 1 || meses > 12) {
        this.mostrarMensaje(`El plazo ${n} debe durar de 1 a 12 meses.`, true);
        return false;
      }
    }
    return true;
  }

  private moverIndice(indice: number | null, desde: number, hacia: number): number | null {
    if (indice == null) return null;
    if (indice === desde) return hacia;
    if (desde < hacia && indice > desde && indice <= hacia) return indice - 1;
    if (hacia < desde && indice >= hacia && indice < desde) return indice + 1;
    return indice;
  }

  private formInicial(): FormState {
    return {
      visible: false,
      modo: 'crear',
      editandoId: null,
      nombreAnterior: '',
      nombre: '',
      mesesVigencia: null,
      definirPlazoProyecto: false,
      mesesProyecto: null,
      definirPlazoTitulacion: false,
      mesesTitulacion: null,
      conservarVigencia: false,
      esResidencia: false,
      esCursoTitulacion: false,
      tipoMentores: 'estandar',
      slug: '',
      carrerasSeleccionadas: [],
      departamentoId: '',
      pasoWizard: 1,
      tipoUso: '',
      origenPlazo: '',
      origenFlujo: '',
      esCeneval: false,
      pasos: [],
      plazos: [],
      nuevoPaso: this.pasoVacio(),
      mentoresExtra: [],
      mentorNuevo: '',
    };
  }
}
