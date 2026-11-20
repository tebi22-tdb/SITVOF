import { Component, EventEmitter, Input, OnChanges, OnDestroy, OnInit, Output, SimpleChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { aplicarValidacionPdfInput } from '../../../core/archivo-pdf';
import {
  EgresadoForm,
  MODALIDADES_CURSO_TITULACION,
  MODALIDADES_TITULACION,
  esModalidadTitulacionCeneval,
  esModalidadTitulacionCurso,
  esModalidadTitulacionHabilitada,
} from '../../../core/datos';
import { EgresadoDetail, EgresadoService } from '../../../services/egresado.service';
import { CatalogoService, ModalidadCatalogo } from '../../../services/catalogo.service';
import { DocenteItem, DocenteService } from '../../../services/docente.service';
import { Subject } from 'rxjs';
import { combineLatest, debounceTime, distinctUntilChanged, switchMap, of, takeUntil, catchError, startWith } from 'rxjs';

export interface AgregarEgresadoPayload {
  datos: EgresadoForm;
  archivo: File | null;
}

export interface ActualizarEgresadoPayload {
  id: string;
  datos: EgresadoForm;
  archivo: File | null;
}

export interface NuevoProcesoVencidoPayload {
  id: string;
  datos: EgresadoForm;
  archivo: File | null;
}

@Component({
  selector: 'app-nuevo-egresado',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  templateUrl: './nuevo-egresado.component.html',
  styleUrl: './nuevo-egresado.component.css',
})
export class NuevoEgresadoComponent implements OnChanges, OnInit, OnDestroy {
  @Input() egresadoParaEditar: EgresadoDetail | null = null;
  @Input() guardando = false;
  @Output() cancelar = new EventEmitter<void>();
  @Output() agregar = new EventEmitter<AgregarEgresadoPayload>();
  @Output() actualizar = new EventEmitter<ActualizarEgresadoPayload>();
  @Output() nuevoProcesoVencido = new EventEmitter<NuevoProcesoVencidoPayload>();

  carreras: string[] = [];
  niveles: string[] = [];
  modalidadesCatalogo: ModalidadCatalogo[] = [];

  get opcionesModalidadTitulacion(): string[] {
    const extra = this.modalidadesCatalogo
      .filter(m => m.tipoUso === 'titulacion')
      .map(m => m.nombre);
    return [...MODALIDADES_TITULACION, ...extra.filter(n => !MODALIDADES_TITULACION.includes(n as typeof MODALIDADES_TITULACION[number]))];
  }

  archivoSeleccionado: File | null = null;
  /** Al editar: true si el usuario quiere quitar el archivo actual. */
  quitarArchivoSeleccionado = false;
  /** True cuando intentaron guardar sin adjuntar archivo (para marcar el campo en rojo). */
  archivoRequeridoError = false;
  /** Constancia: fecha de expedición posterior al registro del Anexo XXXI. */
  errorFechaConstanciaPosterior = false;
  /** True cuando se intentó enviar el formulario con campos obligatorios vacíos o inválidos. */
  mostrarModalCamposIncompletos = false;
  form: FormGroup;

  originalidadEstado: 'LIBRE' | 'CONFIRMAR' | 'ADVERTENCIA' | 'BLOQUEADO' | 'comprobando' | null = null;
  originalidadTituloSimilar: string | null = null;
  /** cupo_modalidad | otra_modalidad | misma_modalidad_existente */
  originalidadMotivo: string | null = null;
  /** null = sin responder; true = aceptó título compartido; false = rechazó */
  tituloCompartidoConfirmado: boolean | null = null;
  /** Registros existentes con el mismo título en la misma modalidad. */
  originalidadCoincidencias = 0;
  /** vencido | titulado | en_proceso cuando originalidadEstado === BLOQUEADO */
  originalidadExpedienteEstado: 'vencido' | 'titulado' | 'en_proceso' | null = null;

  /** true solo cuando es Residencia Profesional y hay exactamente 1 coincidencia (proyecto en pareja). */
  esParejaResidencia = false;
  companeroNombre: string | null = null;
  /** El coordinador debe marcar esta casilla para confirmar que es el mismo proyecto en pareja. */
  parejaResidenciaConfirmada = false;
  readonly maxTituloParejaResidencia = 2;

  controlAltaEstado: 'LIBRE' | 'BLOQUEADO' | 'RETOMAR' | 'comprobando' | null = null;
  controlAltaExpedienteEstado: 'vencido' | 'titulado' | 'en_proceso' | null = null;

  /** true cuando se detectó un proceso vencido y el form actúa como "nuevo proceso" */
  modoNuevoProceso = false;
  egresadoVencidoId: string | null = null;
  /** Modalidades bloqueadas porque ya se usaron en procesos anteriores (vencidos/titulados) */
  modalidadesVencidas: string[] = [];
  docentesLista: DocenteItem[] = [];
  private egresadoVencidoNc: string | null = null;
  private camposPersonales = ['nombre', 'apellido_paterno', 'apellido_materno', 'genero', 'carrera', 'nivel', 'direccion', 'telefono', 'correo_electronico'];

  private destroy$ = new Subject<void>();
  opcionesTitulacion: { nombre: string; valor: string }[] = [
    { nombre: 'Titulación', valor: 'titulacion' },
    { nombre: 'Titulación integral', valor: 'titulacion_integral' },
    { nombre: 'Curso de titulación', valor: 'curso_titulacion' },
  ];

  constructor(
    private fb: FormBuilder,
    private egresadoService: EgresadoService,
    public catalogoService: CatalogoService,
    private docenteService: DocenteService,
  ) {
    this.form = this.fb.group({
      numero_control: ['', Validators.required],
      nombre: ['', Validators.required],
      apellido_paterno: ['', Validators.required],
      apellido_materno: ['', Validators.required],
      carrera: ['', Validators.required],
      nivel: ['', Validators.required],
      direccion: ['', Validators.required],
      telefono: ['', Validators.required],
      correo_electronico: ['', [Validators.required, Validators.email]],
      genero: ['', Validators.required],
      tipo_titulacion: ['', Validators.required],
      modalidad_titulacion: [''],
      nombre_proyecto: ['', Validators.required],
      modalidad: ['', Validators.required],
      curso_titulacion: [false],
      asesor_interno: [''],
      asesor_externo: [''],
      director: [''],
      asesor_1: [''],
      asesor_2: [''],
      fecha_registro_anexo: ['', Validators.required],
      fecha_expedicion_constancia: ['', Validators.required],
      observaciones: [''], // único opcional (notas)
    });
    this.actualizarValidadoresPorTipoTitulacion();
    this.form.get('tipo_titulacion')?.valueChanges.subscribe(() => this.actualizarValidadoresPorTipoTitulacion());
    this.form.get('modalidad_titulacion')?.valueChanges.subscribe(() => this.actualizarValidadoresPorModalidadTitulacion());
    this.form.get('modalidad')?.valueChanges.subscribe(() => this.actualizarValidadoresPorModalidad());
    this.form.get('fecha_registro_anexo')?.valueChanges.subscribe(() => this.revisarFechasDocumentos());
    this.form.get('fecha_expedicion_constancia')?.valueChanges.subscribe(() => this.revisarFechasDocumentos());
  }

  private revisarFechasDocumentos(): void {
    if (this.esCenevalModalidad) {
      this.errorFechaConstanciaPosterior = false;
      return;
    }
    const reg = (this.form.get('fecha_registro_anexo')?.value as string) || '';
    const exp = (this.form.get('fecha_expedicion_constancia')?.value as string) || '';
    this.errorFechaConstanciaPosterior = !!(reg && exp && exp > reg);
  }

  get esTitulacionIntegral(): boolean {
    return this.form.get('tipo_titulacion')?.value === 'titulacion_integral';
  }

  get esCursoTitulacionTipo(): boolean {
    return this.form.get('tipo_titulacion')?.value === 'curso_titulacion';
  }

  /** Titulación integral, curso y un tipo agregado en el catálogo usan el mismo formulario de modalidad. */
  get esFormularioIntegral(): boolean {
    return this.esTitulacionIntegral || this.esCursoTitulacionTipo || this.esTipoPersonalizado;
  }

  get esTipoPersonalizado(): boolean {
    const tipo = ((this.form.get('tipo_titulacion')?.value as string) || '').trim();
    return !!tipo && tipo !== 'titulacion' && tipo !== 'titulacion_integral' && tipo !== 'curso_titulacion';
  }

  get tiposPersonalizados(): string[] {
    const fijos = new Set(['', 'titulacion', 'integral', 'curso']);
    return [...new Set(
      this.modalidadesCatalogo
        .map(m => (m.tipoUso || '').trim())
        .filter(t => !fijos.has(t)),
    )];
  }

  get esTitulacion(): boolean {
    return this.form.get('tipo_titulacion')?.value === 'titulacion';
  }

  /** Titulación tradicional con modalidad que ya tiene flujo (tesis, curso o CENEVAL). */
  get esTitulacionModalidadHabilitada(): boolean {
    const nombre = this.form.get('modalidad_titulacion')?.value as string;
    if (!this.esTitulacion) return false;
    if (esModalidadTitulacionHabilitada(nombre)) return true;
    return this.modalidadesCatalogo.some(m => m.tipoUso === 'titulacion' && m.nombre === nombre);
  }

  /** Muestra campos de proyecto/documentos (integral o modalidad de titulación habilitada). */
  get esFlujoProyectoActivo(): boolean {
    return this.esFormularioIntegral || this.esTitulacionModalidadHabilitada;
  }

  get esCenevalModalidad(): boolean {
    const directa = (this.form.get('modalidad')?.value as string) || '';
    if (this.catalogoService.esCeneval(directa)) return true;
    const especifica = (this.form.get('modalidad_titulacion')?.value as string) || '';
    return this.esTitulacion && (esModalidadTitulacionCeneval(especifica) || this.catalogoService.esCeneval(especifica));
  }

  /** Integral: modalidades que no son de curso. Curso: solo las marcadas como curso de titulación. */
  get opcionesModalidad(): string[] {
    if (this.esCursoTitulacionTipo) {
      const soloCurso = this.modalidadesCatalogo.filter(m => m.esCursoTitulacion).map(m => m.nombre);
      return soloCurso.length ? soloCurso : [...MODALIDADES_CURSO_TITULACION];
    }
    if (this.esTipoPersonalizado) {
      const tipo = ((this.form.get('tipo_titulacion')?.value as string) || '').trim();
      return this.modalidadesCatalogo
        .filter(m => {
          const uso = (m.tipoUso || '').trim();
          return uso === tipo || uso.toLowerCase() === tipo.toLowerCase();
        })
        .map(m => m.nombre);
    }
    return this.modalidadesCatalogo
      .filter(m => {
        const uso = (m.tipoUso || '').trim();
        return (uso === '' || uso === 'integral') && !m.esCursoTitulacion;
      })
      .map(m => m.nombre);
  }

  ngOnInit(): void {
    this.catalogoService.carreras$.pipe(takeUntil(this.destroy$))
      .subscribe(lista => (this.carreras = lista));
    this.catalogoService.niveles$.pipe(takeUntil(this.destroy$))
      .subscribe(lista => (this.niveles = lista));
    this.catalogoService.modalidades$.pipe(takeUntil(this.destroy$))
      .subscribe(lista => (this.modalidadesCatalogo = lista));
    this.catalogoService.opciones$.pipe(takeUntil(this.destroy$))
      .subscribe(lista => {
        const base = [
          { nombre: 'Titulación', valor: 'titulacion' },
          { nombre: 'Titulación integral', valor: 'titulacion_integral' },
          { nombre: 'Curso de titulación', valor: 'curso_titulacion' },
        ];
        const extras = lista
          .filter(o => o.clave !== 'titulacion' && o.clave !== 'integral' && o.clave !== 'curso')
          .map(o => ({ nombre: o.nombre, valor: o.nombre }));
        this.opcionesTitulacion = [...base, ...extras];
      });

    this.docenteService
      .listar()
      .pipe(takeUntil(this.destroy$))
      .subscribe({
        next: (lista) => {
          this.docentesLista = lista;
        },
        error: () => {},
      });

    this.form
      .get('curso_titulacion')
      ?.valueChanges.pipe(startWith(this.form.get('curso_titulacion')?.value), distinctUntilChanged(), takeUntil(this.destroy$))
      .subscribe(() => this.alinearModalidadSiNoAplica());

    combineLatest([
      this.form.get('nombre_proyecto')!.valueChanges.pipe(startWith(this.form.get('nombre_proyecto')!.value)),
      this.form.get('modalidad')!.valueChanges.pipe(startWith(this.form.get('modalidad')!.value)),
    ])
      .pipe(
        debounceTime(600),
        switchMap(([titulo, modalidad]) => {
          if (!this.esFlujoProyectoActivo) {
            this.originalidadEstado = null;
            this.originalidadTituloSimilar = null;
            this.originalidadMotivo = null;
            this.originalidadExpedienteEstado = null;
            this.tituloCompartidoConfirmado = null;
            this.originalidadCoincidencias = 0;
            this.esParejaResidencia = false;
            this.companeroNombre = null;
            this.parejaResidenciaConfirmada = false;
            return of(null);
          }
          if (this.esCenevalModalidad) {
            this.originalidadEstado = null;
            this.originalidadTituloSimilar = null;
            this.originalidadMotivo = null;
            this.originalidadExpedienteEstado = null;
            this.tituloCompartidoConfirmado = null;
            this.originalidadCoincidencias = 0;
            this.esParejaResidencia = false;
            this.companeroNombre = null;
            this.parejaResidenciaConfirmada = false;
            return of(null);
          }
          const t = (titulo || '').trim();
          if (t.length < 5) {
            this.originalidadEstado = null;
            this.originalidadTituloSimilar = null;
            this.originalidadMotivo = null;
            this.originalidadExpedienteEstado = null;
            this.tituloCompartidoConfirmado = null;
            this.originalidadCoincidencias = 0;
            this.esParejaResidencia = false;
            this.companeroNombre = null;
            this.parejaResidenciaConfirmada = false;
            return of(null);
          }
          this.originalidadEstado = 'comprobando';
          this.tituloCompartidoConfirmado = null;
          this.originalidadCoincidencias = 0;
          const excluirId = this.egresadoParaEditar?.id;
          const mod = (modalidad || '').toString();
          return this.egresadoService.verificarOriginalidad(t, excluirId, mod).pipe(catchError(() => of(null)));
        }),
        takeUntil(this.destroy$),
      )
      .subscribe((resultado) => {
        if (resultado === null) {
          if (this.originalidadEstado === 'comprobando') this.originalidadEstado = null;
          return;
        }
        const estado = resultado.estado as 'LIBRE' | 'CONFIRMAR' | 'ADVERTENCIA' | 'BLOQUEADO';
        // En edición: CONFIRMAR (cupo pareja) no exige checkbox; BLOQUEADO/ADVERTENCIA sí impiden guardar.
        this.originalidadEstado = this.editando && estado === 'CONFIRMAR' ? 'LIBRE' : estado;
        this.originalidadTituloSimilar = resultado.titulo_similar || null;
        this.originalidadMotivo = (resultado.motivo || '').trim() || null;
        this.originalidadCoincidencias = resultado.coincidencias_misma_modalidad ?? 0;
        const ex = (resultado.expediente_estado || '').trim();
        this.originalidadExpedienteEstado =
          ex === 'vencido' || ex === 'titulado' || ex === 'en_proceso' ? ex : null;
        this.esParejaResidencia = !!resultado.es_pareja_residencia;
        this.companeroNombre = resultado.companero_nombre || null;
        if (this.originalidadEstado !== 'CONFIRMAR') {
          this.tituloCompartidoConfirmado = null;
          this.parejaResidenciaConfirmada = false;
        }
      });

    this.form.get('numero_control')!.valueChanges.pipe(
      debounceTime(500),
      distinctUntilChanged(),
      switchMap((nc) => {
        const t = (nc || '').trim();
        if (this.egresadoVencidoNc && t !== this.egresadoVencidoNc) {
          this.salirModoNuevoProceso();
        }
        if (t.length < 4) {
          this.controlAltaEstado = null;
          this.controlAltaExpedienteEstado = null;
          if (this.modoNuevoProceso) this.salirModoNuevoProceso();
          return of(null);
        }
        this.controlAltaEstado = 'comprobando';
        const excluirId = this.egresadoParaEditar?.id;
        const modalidad = (this.form.get('modalidad')?.value || '').toString();
        return this.egresadoService.verificarNumeroControlAlta(t, modalidad, excluirId).pipe(catchError(() => of(null)));
      }),
      takeUntil(this.destroy$),
    ).subscribe((resultado) => {
      if (resultado === null) {
        if (this.controlAltaEstado === 'comprobando') this.controlAltaEstado = null;
        this.controlAltaExpedienteEstado = null;
        return;
      }
      this.aplicarResultadoControlAlta(resultado);
    });

    this.form.get('modalidad')!.valueChanges.pipe(
      debounceTime(250),
      distinctUntilChanged(),
      switchMap((modalidad) => {
        const numeroControl = (this.form.get('numero_control')?.value || '').trim();
        if (numeroControl.length < 4) return of(null);
        this.controlAltaEstado = 'comprobando';
        const excluirId = this.egresadoParaEditar?.id;
        return this.egresadoService
          .verificarNumeroControlAlta(numeroControl, (modalidad || '').toString(), excluirId)
          .pipe(catchError(() => of(null)));
      }),
      takeUntil(this.destroy$),
    ).subscribe((resultado) => {
      if (resultado === null) return;
      this.aplicarResultadoControlAlta(resultado);
    });
  }

  /**
   * Si el NC pertenece a un expediente vencido, siempre entra a «nuevo proceso»
   * (mismo alumno + historial), nunca a un alta duplicada — aunque la modalidad nueva ya esté elegida.
   */
  private aplicarResultadoControlAlta(resultado: {
    estado: string;
    expediente_estado?: string;
    egresado_id?: string;
  }): void {
    const estado = (resultado.estado || '').trim().toUpperCase();
    this.controlAltaEstado =
      estado === 'BLOQUEADO' || estado === 'RETOMAR' || estado === 'LIBRE'
        ? (estado as 'LIBRE' | 'BLOQUEADO' | 'RETOMAR')
        : 'LIBRE';
    const ex = (resultado.expediente_estado || '').trim();
    this.controlAltaExpedienteEstado =
      ex === 'vencido' || ex === 'titulado' || ex === 'en_proceso' ? ex : null;

    const id = (resultado.egresado_id || '').trim();
    const nc = (this.form.get('numero_control')?.value || '').trim();
    if (!this.editando && id && ex === 'vencido') {
      if (!this.modoNuevoProceso || this.egresadoVencidoId !== id) {
        this.entrarModoNuevoProceso(id, nc);
      }
    }
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  ngOnChanges(changes: SimpleChanges): void {
    const d = changes['egresadoParaEditar']?.currentValue as EgresadoDetail | null;
    if (d) {
      if (this.modoNuevoProceso) this.salirModoNuevoProceso();
      const isoToDate = (s?: string) => (s ? s.slice(0, 10) : '');
      const modalidadGuardada = d.datos_proyecto.modalidad || '';
      const tipoBase =
        d.datos_proyecto.tipo_titulacion ||
        (esModalidadTitulacionHabilitada(modalidadGuardada) ? 'titulacion' : 'titulacion_integral');
      const tipoGuardado =
        tipoBase !== 'titulacion' && d.datos_proyecto.curso_titulacion === 'si'
          ? 'curso_titulacion'
          : tipoBase;
      const esTradicional = tipoGuardado === 'titulacion';
      this.form.patchValue({
        numero_control: d.numero_control,
        nombre: d.datos_personales.nombre,
        apellido_paterno: d.datos_personales.apellido_paterno,
        apellido_materno: d.datos_personales.apellido_materno,
        carrera: d.datos_personales.carrera,
        nivel: d.datos_personales.nivel,
        direccion: d.datos_personales.direccion || '',
        telefono: d.datos_personales.telefono || '',
        correo_electronico: d.datos_personales.correo_electronico || '',
        genero: d.datos_personales.genero || '',
        tipo_titulacion: tipoGuardado,
        modalidad_titulacion: esTradicional ? modalidadGuardada : '',
        nombre_proyecto: d.datos_proyecto.nombre_proyecto || '',
        modalidad: modalidadGuardada,
        curso_titulacion: d.datos_proyecto.curso_titulacion === 'si',
        asesor_interno: d.datos_proyecto.asesor_interno || '',
        asesor_externo: d.datos_proyecto.asesor_externo || '',
        director: d.datos_proyecto.director || '',
        asesor_1: d.datos_proyecto.asesor_1 || '',
        asesor_2: d.datos_proyecto.asesor_2 || '',
        fecha_registro_anexo: isoToDate(d.documentos?.anexo_xxxi?.fecha_registro),
        fecha_expedicion_constancia: isoToDate(d.documentos?.constancia_no_inconveniencia?.fecha_expedicion),
        observaciones: '',
      });
      this.actualizarValidadoresPorTipoTitulacion();
      if (esTradicional) this.actualizarValidadoresPorModalidadTitulacion();
      this.quitarArchivoSeleccionado = false;
    }
  }

  get tieneArchivoActual(): boolean {
    const adj = this.egresadoParaEditar?.documento_adjunto;
    return !!(adj?.nombre_original || (adj?.tamanio_bytes && adj.tamanio_bytes > 0));
  }

  get editando(): boolean {
    return !!this.egresadoParaEditar;
  }

  /** Mensaje bajo «Nombre del proyecto» cuando el título está bloqueado. */
  get requiereConfirmacionTituloCompartido(): boolean {
    return !this.editando && this.originalidadEstado === 'CONFIRMAR' && !this.esParejaResidencia;
  }

  /** Caso especial: Residencia Profesional en pareja, ya hay 1 egresado con este título. */
  get muestraParejaResidenciaReconocida(): boolean {
    return !this.editando && this.originalidadEstado === 'CONFIRMAR' && this.esParejaResidencia;
  }

  get mensajeParejaResidencia(): string {
    return 'Este título ya está registrado. Marca la casilla para confirmar usar este título nuevamente.';
  }

  toggleParejaResidenciaConfirmada(): void {
    this.parejaResidenciaConfirmada = !this.parejaResidenciaConfirmada;
  }

  /**
   * Cupo del mismo título en la misma modalidad:
   * Tesis / Proyecto de Investigación / Residencia → 2; resto (Tesina, Monografía, …) → 1.
   */
  get maxTituloPorModalidad(): number {
    const raw = (this.form.get('modalidad')?.value as string) || '';
    const mod = raw.toLowerCase().normalize('NFD').replace(/\p{M}/gu, '');
    if (this.catalogoService.esResidencia(raw)) return 2;
    if (mod.includes('tesina')) return 1;
    if (mod.includes('tesis')) return 2;
    if (mod.includes('proyecto') && mod.includes('investigacion')) return 2;
    return 1;
  }

  /** Texto del cuadro Sí/No (solo modalidades con cupo ≥ 2, p. ej. Tesis). */
  get mensajeConfirmacionTituloCompartido(): string {
    const max = this.maxTituloPorModalidad;
    return `Este título ya está registrado en 1 expediente de la misma modalidad. ¿Desea registrar un segundo egresado con el mismo título? (Máximo ${max} por modalidad.)`;
  }

  get puedeRegistrarTituloProyecto(): boolean {
    if (this.esCenevalModalidad) return true;
    if (this.originalidadEstado === 'BLOQUEADO' || this.originalidadEstado === 'ADVERTENCIA') return false;
    if (this.muestraParejaResidenciaReconocida) return this.parejaResidenciaConfirmada === true;
    if (this.requiereConfirmacionTituloCompartido) return this.tituloCompartidoConfirmado === true;
    return true;
  }

  aceptarTituloCompartido(): void {
    this.tituloCompartidoConfirmado = true;
  }

  rechazarTituloCompartido(): void {
    this.tituloCompartidoConfirmado = false;
    this.form.patchValue({ nombre_proyecto: '' });
    this.originalidadEstado = null;
    this.originalidadMotivo = null;
    this.originalidadTituloSimilar = null;
  }

  get mensajeBloqueoNombreProyecto(): string {
    if (this.originalidadEstado !== 'BLOQUEADO') return '';
    if (this.originalidadMotivo === 'titulo_vencido') {
      return 'Este título ya se usó en un proceso vencido. Debes registrar un título distinto.';
    }
    if (this.originalidadMotivo === 'titulo_concluido') {
      return 'Este título ya está registrado. Debes registrar un título distinto.';
    }
    if (this.originalidadMotivo === 'cupo_pareja_residencia') {
      return `Ya existen ${this.maxTituloParejaResidencia} registros con este título, no se puede realizar otro registro.`;
    }
    if (this.originalidadMotivo === 'cupo_modalidad') {
      return `Ya hay ${this.maxTituloPorModalidad} egresados con este nombre en la misma modalidad. No se puede registrar otro.`;
    }
    if (this.originalidadMotivo === 'otra_modalidad') {
      return 'Este nombre ya está registrado en otra modalidad; usa un título distinto.';
    }
    return 'Este nombre de proyecto ya existe.';
  }

  /** Mensaje bajo «Número de control» cuando ya está registrado. */
  get mensajeBloqueoNumeroControl(): string {
    if (this.controlAltaEstado === 'RETOMAR' && this.modoNuevoProceso) {
      return 'Expediente vencido encontrado. Se registrará un nuevo proceso en el mismo alumno (el anterior queda en historial).';
    }
    if (this.controlAltaEstado !== 'BLOQUEADO') return '';
    if (this.modoNuevoProceso && this.controlAltaExpedienteEstado === 'vencido') {
      return 'Selecciona una modalidad diferente a la usada en el proceso vencido para continuar.';
    }
    if (this.controlAltaExpedienteEstado === 'vencido') {
      return 'Este número de control ya tuvo un expediente vencido. Para registrarlo nuevamente debes elegir una modalidad distinta.';
    }
    if (this.controlAltaExpedienteEstado === 'titulado') {
      return 'Este número de control ya concluyó su proceso de titulación. No es posible darlo de alta nuevamente.';
    }
    return 'Este número de control ya está registrado; el expediente se encuentra en proceso.';
  }

  /** Devuelve true si la modalidad ya fue usada en un proceso cerrado de este egresado */
  esModalidadVencida(nombre: string): boolean {
    const n = nombre.trim().toLowerCase();
    return this.modalidadesVencidas.some(m => m === n);
  }

  private entrarModoNuevoProceso(egresadoId: string, nc: string): void {
    this.egresadoVencidoId = egresadoId;
    this.egresadoVencidoNc = nc;
    this.modoNuevoProceso = true;
    this.egresadoService.obtenerPorId(egresadoId).subscribe({
      next: (detalle) => {
        const p = detalle.datos_personales;
        this.form.patchValue({
          nombre: p.nombre || '',
          apellido_paterno: p.apellido_paterno || '',
          apellido_materno: p.apellido_materno || '',
          carrera: p.carrera || '',
          nivel: p.nivel || '',
          direccion: p.direccion || '',
          telefono: p.telefono || '',
          correo_electronico: p.correo_electronico || '',
        });
        this.camposPersonales.forEach(f => this.form.get(f)?.disable());

        // Recopilar todas las modalidades ya usadas en procesos cerrados
        const bloqueadas = new Set<string>();
        if (detalle.datos_proyecto?.modalidad) {
          bloqueadas.add(detalle.datos_proyecto.modalidad.trim().toLowerCase());
        }
        for (const anterior of (detalle.procesos_anteriores || [])) {
          if (anterior.modalidad) bloqueadas.add(anterior.modalidad.trim().toLowerCase());
        }
        this.modalidadesVencidas = [...bloqueadas];
      },
    });
  }

  private salirModoNuevoProceso(): void {
    this.modoNuevoProceso = false;
    this.egresadoVencidoId = null;
    this.egresadoVencidoNc = null;
    this.modalidadesVencidas = [];
    this.camposPersonales.forEach(f => this.form.get(f)?.enable());
    this.form.patchValue({
      nombre: '', apellido_paterno: '', apellido_materno: '',
      carrera: '', nivel: '', direccion: '', telefono: '', correo_electronico: '',
    });
  }

  /** Si la modalidad actual no está en las opciones visibles (por cambiar el checkbox), se limpia. */
  private alinearModalidadSiNoAplica(): void {
    if (!this.esFormularioIntegral) return;
    const opts = this.opcionesModalidad;
    const cur = this.form.get('modalidad')?.value as string | undefined;
    if (cur && !opts.includes(cur)) {
      this.form.patchValue({ modalidad: '' });
    }
    this.actualizarValidadoresPorModalidad();
  }

  private limpiarValidadoresProyectoIntegral(): void {
    for (const c of [
      'nombre_proyecto',
      'modalidad',
      'asesor_interno',
      'asesor_externo',
      'director',
      'asesor_1',
      'asesor_2',
      'fecha_registro_anexo',
      'fecha_expedicion_constancia',
    ]) {
      this.form.get(c)?.clearValidators();
      this.form.get(c)?.updateValueAndValidity({ emitEvent: false });
    }
    this.errorFechaConstanciaPosterior = false;
    this.originalidadEstado = null;
    this.originalidadTituloSimilar = null;
    this.originalidadMotivo = null;
    this.originalidadExpedienteEstado = null;
    this.tituloCompartidoConfirmado = null;
    this.originalidadCoincidencias = 0;
  }

  private actualizarValidadoresPorTipoTitulacion(): void {
    const tipo = this.form.get('tipo_titulacion')?.value as string;
    if (tipo === 'titulacion_integral' || tipo === 'curso_titulacion' || this.esTipoPersonalizado) {
      this.form.patchValue(
        { modalidad_titulacion: '', curso_titulacion: tipo === 'curso_titulacion' },
        { emitEvent: false },
      );
      this.form.get('modalidad_titulacion')?.clearValidators();
      this.form.get('modalidad_titulacion')?.updateValueAndValidity({ emitEvent: false });
      this.form.get('modalidad')?.setValidators(Validators.required);
      this.alinearModalidadSiNoAplica();
      return;
    }
    if (tipo === 'titulacion') {
      this.form.patchValue({ curso_titulacion: false }, { emitEvent: false });
      this.form.get('modalidad')?.clearValidators();
      this.form.get('modalidad')?.updateValueAndValidity({ emitEvent: false });
      this.form.get('modalidad_titulacion')?.setValidators(Validators.required);
      this.form.get('modalidad_titulacion')?.updateValueAndValidity({ emitEvent: false });
      this.actualizarValidadoresPorModalidadTitulacion();
      return;
    }
    this.form.patchValue({ modalidad: '', modalidad_titulacion: '' }, { emitEvent: false });
    this.form.get('modalidad_titulacion')?.clearValidators();
    this.form.get('modalidad_titulacion')?.updateValueAndValidity({ emitEvent: false });
    this.limpiarValidadoresProyectoIntegral();
  }

  /**
   * Titulación tradicional:
   * tesis y proyectos = flujo de tesis; cursos especiales = curso de titulación; examen (CENEVAL) = CENEVAL.
   */
  private actualizarValidadoresPorModalidadTitulacion(): void {
    if (!this.esTitulacion) return;
    const modTit = ((this.form.get('modalidad_titulacion')?.value as string) || '').trim();
    if (!esModalidadTitulacionHabilitada(modTit)) {
      this.form.patchValue({ modalidad: '', curso_titulacion: false }, { emitEvent: false });
      this.limpiarValidadoresProyectoIntegral();
      return;
    }
    const esCeneval = esModalidadTitulacionCeneval(modTit);
    const esCurso = esModalidadTitulacionCurso(modTit);
    this.form.patchValue({ modalidad: modTit, curso_titulacion: esCurso }, { emitEvent: false });
    this.form.get('modalidad')?.setValidators(Validators.required);
    this.form.get('modalidad')?.updateValueAndValidity({ emitEvent: false });
    if (esCeneval) this.aplicarValidadoresEstiloCeneval();
    else this.aplicarValidadoresEstiloTesis();
  }

  /** Carta de no inconveniencia, sin nombre de proyecto ni mentores. Igual que CENEVAL. */
  private aplicarValidadoresEstiloCeneval(): void {
    const required = Validators.required;
    for (const c of ['asesor_interno', 'asesor_externo', 'director', 'asesor_1', 'asesor_2', 'nombre_proyecto', 'fecha_registro_anexo']) {
      this.form.get(c)?.clearValidators();
    }
    this.form.get('fecha_expedicion_constancia')?.setValidators(required);
    this.form.patchValue(
      {
        nombre_proyecto: 'EXAMEN CENEVAL',
        curso_titulacion: false,
        fecha_registro_anexo: '',
        director: '',
        asesor_1: '',
        asesor_2: '',
        asesor_interno: '',
        asesor_externo: '',
      },
      { emitEvent: false },
    );
    this.originalidadEstado = null;
    this.originalidadTituloSimilar = null;
    this.originalidadMotivo = null;
    this.errorFechaConstanciaPosterior = false;
    for (const c of [
      'nombre_proyecto', 'asesor_interno', 'asesor_externo', 'director', 'asesor_1', 'asesor_2',
      'fecha_registro_anexo', 'fecha_expedicion_constancia',
    ]) {
      this.form.get(c)?.updateValueAndValidity({ emitEvent: false });
    }
  }

  /** Mentores y fechas iguales a Tesis / Curso de titulación (flujo no-residencia). */
  private aplicarValidadoresEstiloTesis(): void {
    const required = Validators.required;
    const nombre = ((this.form.get('nombre_proyecto')?.value as string) || '').trim().toUpperCase();
    if (nombre === 'EXAMEN CENEVAL') {
      this.form.patchValue({ nombre_proyecto: '' }, { emitEvent: false });
    }
    this.form.get('asesor_interno')?.clearValidators();
    this.form.get('asesor_externo')?.clearValidators();
    this.form.get('director')?.setValidators(required);
    this.form.get('asesor_1')?.setValidators(required);
    this.form.get('asesor_2')?.setValidators(required);
    this.form.get('nombre_proyecto')?.setValidators(required);
    this.form.get('fecha_registro_anexo')?.setValidators(required);
    this.form.get('fecha_expedicion_constancia')?.setValidators(required);
    for (const c of [
      'nombre_proyecto',
      'asesor_interno',
      'asesor_externo',
      'director',
      'asesor_1',
      'asesor_2',
      'fecha_registro_anexo',
      'fecha_expedicion_constancia',
    ]) {
      this.form.get(c)?.updateValueAndValidity({ emitEvent: false });
    }
    this.revisarFechasDocumentos();
  }

  /** Según modalidad: mentores, fechas de documentos (CENEVAL: carta de no inconveniencia y archivo). */
  private actualizarValidadoresPorModalidad(): void {
    if (!this.esFormularioIntegral) return;
    const modalidad = this.form.get('modalidad')?.value as string;
    const esCeneval = this.catalogoService.esCeneval(modalidad ?? '');
    const tipo = this.catalogoService.tipoMentoresPorNombre(modalidad ?? '');
    const required = Validators.required;
    if (tipo === 'residencia') {
      this.form.get('asesor_interno')?.setValidators(required);
      this.form.get('asesor_externo')?.setValidators(required);
      this.form.get('director')?.clearValidators();
      this.form.get('asesor_1')?.clearValidators();
      this.form.get('asesor_2')?.clearValidators();
    } else if (tipo === 'ninguno' || esCeneval) {
      this.form.get('asesor_interno')?.clearValidators();
      this.form.get('asesor_externo')?.clearValidators();
      this.form.get('director')?.clearValidators();
      this.form.get('asesor_1')?.clearValidators();
      this.form.get('asesor_2')?.clearValidators();
      this.form.patchValue({ director: '', asesor_1: '', asesor_2: '', asesor_interno: '', asesor_externo: '' }, { emitEvent: false });
    } else {
      this.form.get('asesor_interno')?.clearValidators();
      this.form.get('asesor_externo')?.clearValidators();
      this.form.get('director')?.setValidators(required);
      this.form.get('asesor_1')?.setValidators(required);
      this.form.get('asesor_2')?.setValidators(required);
    }
    if (esCeneval) {
      this.form.get('nombre_proyecto')?.clearValidators();
      this.form.get('fecha_registro_anexo')?.clearValidators();
      this.form.get('fecha_expedicion_constancia')?.setValidators(required);
      this.form.patchValue(
        {
          nombre_proyecto: 'EXAMEN CENEVAL',
          curso_titulacion: false,
          fecha_registro_anexo: '',
        },
        { emitEvent: false },
      );
      this.originalidadEstado = null;
      this.originalidadTituloSimilar = null;
      this.originalidadMotivo = null;
    } else {
      this.form.get('nombre_proyecto')?.setValidators(required);
      this.form.get('fecha_registro_anexo')?.setValidators(required);
      this.form.get('fecha_expedicion_constancia')?.setValidators(required);
    }
    for (const c of [
      'nombre_proyecto',
      'asesor_interno',
      'asesor_externo',
      'director',
      'asesor_1',
      'asesor_2',
      'fecha_registro_anexo',
      'fecha_expedicion_constancia',
    ]) {
      this.form.get(c)?.updateValueAndValidity({ emitEvent: false });
    }
    this.revisarFechasDocumentos();
  }

  archivoPdfError = '';

  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    this.archivoRequeridoError = false;
    aplicarValidacionPdfInput(
      input,
      file,
      (ok) => {
        this.archivoSeleccionado = ok;
        this.archivoPdfError = '';
        this.quitarArchivoSeleccionado = false;
      },
      (msg) => {
        this.archivoPdfError = msg;
      },
      () => {
        this.archivoSeleccionado = null;
        this.archivoPdfError = '';
      },
    );
  }

  /** Abre el picker nativo al hacer clic en cualquier parte del input de fecha. */
  abrirPicker(el: HTMLInputElement): void {
    try { el.showPicker(); } catch { /* navegadores sin soporte ignoran silenciosamente */ }
  }

  /** Indica si el control está inválido y ya fue tocado (para marcar en rojo y mostrar "Campo obligatorio"). */
  valorCampo(controlName: string): string {
    return ((this.form.get(controlName)?.value as string) || '').trim();
  }

  docenteEnLista(valor: string): boolean {
    const v = (valor || '').trim();
    if (!v) return true;
    return this.docentesLista.some((d) => d.nombreCompleto === v);
  }

  campoInvalido(controlName: string): boolean {
    const c = this.form.get(controlName);
    return !!(c?.invalid && c?.touched);
  }

  cerrarModalCamposIncompletos(): void {
    this.mostrarModalCamposIncompletos = false;
  }

  onCancelar(): void {
    this.archivoSeleccionado = null;
    this.quitarArchivoSeleccionado = false;
    this.cancelar.emit();
  }

  onQuitarArchivoActual(): void {
    this.quitarArchivoSeleccionado = true;
    this.archivoSeleccionado = null;
  }

  onRestaurarArchivoActual(): void {
    this.quitarArchivoSeleccionado = false;
  }

  onSubmit(): void {
    if (this.guardando) return;
    if (!this.esFlujoProyectoActivo) return;
    this.archivoRequeridoError = false;
    this.revisarFechasDocumentos();
    if (this.form.invalid || this.errorFechaConstanciaPosterior) {
      this.form.markAllAsTouched();
      this.mostrarModalCamposIncompletos = true;
      return;
    }
    if (!this.esCenevalModalidad && !this.puedeRegistrarTituloProyecto) return;
    if (this.controlAltaEstado === 'BLOQUEADO') return;
    if (this.controlAltaEstado === 'RETOMAR' && !this.modoNuevoProceso) return;
    if (!this.editando && !this.archivoSeleccionado) {
      this.archivoRequeridoError = true;
      return;
    }
    const raw = this.form.getRawValue();
    const { modalidad_titulacion: modTit, ...resto } = raw;
    const tipo = (raw.tipo_titulacion as string) || 'titulacion_integral';
    const modalidad =
      tipo === 'titulacion' && esModalidadTitulacionHabilitada(modTit)
        ? (modTit as string).trim()
        : (raw.modalidad as string);
    const esCurso = tipo === 'curso_titulacion' || (tipo === 'titulacion' && esModalidadTitulacionCurso(modTit));
    const datos: EgresadoForm = {
      ...resto,
      modalidad,
      tipo_titulacion: tipo === 'titulacion'
        ? 'titulacion'
        : (tipo === 'curso_titulacion' || tipo === 'titulacion_integral' ? 'titulacion_integral' : tipo.trim().slice(0, 40)),
      curso_titulacion: esCurso ? 'si' : 'no',
      quitar_archivo: this.editando ? this.quitarArchivoSeleccionado : undefined,
    };
    if (this.modoNuevoProceso && this.egresadoVencidoId) {
      this.nuevoProcesoVencido.emit({
        id: this.egresadoVencidoId,
        datos,
        archivo: this.archivoSeleccionado,
      });
    } else if (this.editando && this.egresadoParaEditar) {
      this.actualizar.emit({
        id: this.egresadoParaEditar.id,
        datos,
        archivo: this.archivoSeleccionado,
      });
    } else {
      this.agregar.emit({
        datos,
        archivo: this.archivoSeleccionado,
      });
    }
  }
}
