import { Component, EventEmitter, Input, OnChanges, OnDestroy, OnInit, Output, SimpleChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { EgresadoForm, MODALIDADES_CURSO_TITULACION } from '../../../core/datos';
import { EgresadoDetail, EgresadoService } from '../../../services/egresado.service';
import { CatalogoService, ModalidadCatalogo } from '../../../services/catalogo.service';
import { Subject } from 'rxjs';
import { debounceTime, distinctUntilChanged, switchMap, of, takeUntil, catchError, startWith } from 'rxjs';

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

  archivoSeleccionado: File | null = null;
  /** Al editar: true si el usuario quiere quitar el archivo actual. */
  quitarArchivoSeleccionado = false;
  /** True cuando intentaron guardar sin adjuntar archivo (para marcar el campo en rojo). */
  archivoRequeridoError = false;
  form: FormGroup;

  originalidadEstado: 'LIBRE' | 'ADVERTENCIA' | 'BLOQUEADO' | 'comprobando' | null = null;
  originalidadTituloSimilar: string | null = null;
  /** vencido | titulado | en_proceso cuando originalidadEstado === BLOQUEADO */
  originalidadExpedienteEstado: 'vencido' | 'titulado' | 'en_proceso' | null = null;

  controlAltaEstado: 'LIBRE' | 'BLOQUEADO' | 'comprobando' | null = null;
  controlAltaExpedienteEstado: 'vencido' | 'titulado' | 'en_proceso' | null = null;

  /** true cuando se detectó un proceso vencido y el form actúa como "nuevo proceso" */
  modoNuevoProceso = false;
  egresadoVencidoId: string | null = null;
  /** Modalidades bloqueadas porque ya se usaron en procesos anteriores (vencidos/titulados) */
  modalidadesVencidas: string[] = [];
  private egresadoVencidoNc: string | null = null;
  private camposPersonales = ['nombre', 'apellido_paterno', 'apellido_materno', 'carrera', 'nivel', 'direccion', 'telefono', 'correo_electronico'];

  private destroy$ = new Subject<void>();

  constructor(
    private fb: FormBuilder,
    private egresadoService: EgresadoService,
    public catalogoService: CatalogoService,
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
    this.actualizarValidadoresAsesores();
    this.form.get('modalidad')?.valueChanges.subscribe(() => this.actualizarValidadoresAsesores());
  }

  /** Lista del `<select>` de modalidad: catálogo completo o solo curso de titulación. */
  get opcionesModalidad(): string[] {
    const curso = this.form?.get('curso_titulacion')?.value === true;
    if (curso) {
      const soloCurso = this.modalidadesCatalogo.filter(m => m.esCursoTitulacion).map(m => m.nombre);
      return soloCurso.length ? soloCurso : [...MODALIDADES_CURSO_TITULACION];
    }
    const normales = this.modalidadesCatalogo.filter(m => !m.esCursoTitulacion).map(m => m.nombre);
    return normales.length ? normales : this.modalidadesCatalogo.map(m => m.nombre);
  }

  ngOnInit(): void {
    this.catalogoService.carreras$.pipe(takeUntil(this.destroy$))
      .subscribe(lista => (this.carreras = lista));
    this.catalogoService.niveles$.pipe(takeUntil(this.destroy$))
      .subscribe(lista => (this.niveles = lista));
    this.catalogoService.modalidades$.pipe(takeUntil(this.destroy$))
      .subscribe(lista => (this.modalidadesCatalogo = lista));

    this.form
      .get('curso_titulacion')
      ?.valueChanges.pipe(startWith(this.form.get('curso_titulacion')?.value), distinctUntilChanged(), takeUntil(this.destroy$))
      .subscribe(() => this.alinearModalidadSiNoAplica());

    this.form.get('nombre_proyecto')!.valueChanges.pipe(
      debounceTime(600),
      distinctUntilChanged(),
      switchMap(titulo => {
        const t = (titulo || '').trim();
        if (t.length < 5) {
          this.originalidadEstado = null;
          this.originalidadExpedienteEstado = null;
          return of(null);
        }
        this.originalidadEstado = 'comprobando';
        const excluirId = this.egresadoParaEditar?.id;
        return this.egresadoService.verificarOriginalidad(t, excluirId).pipe(
          catchError(() => of(null)),
        );
      }),
      takeUntil(this.destroy$),
    ).subscribe(resultado => {
      if (resultado === null) {
        if (this.originalidadEstado === 'comprobando') this.originalidadEstado = null;
        this.originalidadExpedienteEstado = null;
        return;
      }
      this.originalidadEstado = resultado.estado as 'LIBRE' | 'ADVERTENCIA' | 'BLOQUEADO';
      this.originalidadTituloSimilar = resultado.titulo_similar || null;
      const ex = (resultado.expediente_estado || '').trim();
      this.originalidadExpedienteEstado =
        ex === 'vencido' || ex === 'titulado' || ex === 'en_proceso' ? ex : null;
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
      this.controlAltaEstado = resultado.estado as 'LIBRE' | 'BLOQUEADO';
      const ex = (resultado.expediente_estado || '').trim();
      this.controlAltaExpedienteEstado =
        ex === 'vencido' || ex === 'titulado' || ex === 'en_proceso' ? ex : null;

      if (!this.modoNuevoProceso && resultado.estado === 'BLOQUEADO' && ex === 'vencido' && resultado.egresado_id) {
        const nc = (this.form.get('numero_control')?.value || '').trim();
        this.entrarModoNuevoProceso(resultado.egresado_id, nc);
      }
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
      this.controlAltaEstado = resultado.estado as 'LIBRE' | 'BLOQUEADO';
      const ex = (resultado.expediente_estado || '').trim();
      this.controlAltaExpedienteEstado =
        ex === 'vencido' || ex === 'titulado' || ex === 'en_proceso' ? ex : null;
    });
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
        nombre_proyecto: d.datos_proyecto.nombre_proyecto || '',
        modalidad: d.datos_proyecto.modalidad || '',
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
      this.actualizarValidadoresAsesores();
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
  get mensajeBloqueoNombreProyecto(): string {
    if (this.originalidadEstado !== 'BLOQUEADO') return '';
    if (this.originalidadExpedienteEstado === 'vencido') {
      return 'Este nombre de proyecto ya existe.';
    }
    if (this.originalidadExpedienteEstado === 'titulado') {
      return 'Este nombre de proyecto ya existe.';
    }
    return 'Este nombre de proyecto ya existe.';
  }

  /** Mensaje bajo «Número de control» cuando ya está registrado. */
  get mensajeBloqueoNumeroControl(): string {
    if (this.controlAltaEstado !== 'BLOQUEADO') return '';
    if (this.modoNuevoProceso && this.controlAltaExpedienteEstado === 'vencido') {
      return 'Selecciona una modalidad diferente a la usada en el proceso vencido para continuar.';
    }
    if (this.controlAltaExpedienteEstado === 'vencido') {
      return 'Este número de control ya tuvo un expediente vencido. Para registrarlo nuevamente debes elegir una modalidad distinta.';
    }
    if (this.controlAltaExpedienteEstado === 'titulado') {
      return 'Este número de control ya fue usado en un expediente titulado. Para registrarlo nuevamente debes elegir una modalidad distinta.';
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
    const opts = this.opcionesModalidad;
    const cur = this.form.get('modalidad')?.value as string | undefined;
    if (cur && !opts.includes(cur)) {
      this.form.patchValue({ modalidad: '' });
    }
    this.actualizarValidadoresAsesores();
  }

  /** Según tipoMentores de la modalidad, exige los campos correspondientes. */
  private actualizarValidadoresAsesores(): void {
    const modalidad = this.form.get('modalidad')?.value;
    const tipo = this.catalogoService.tipoMentoresPorNombre(modalidad ?? '');
    const required = Validators.required;
    if (tipo === 'residencia') {
      this.form.get('asesor_interno')?.setValidators(required);
      this.form.get('asesor_externo')?.setValidators(required);
      this.form.get('director')?.clearValidators();
      this.form.get('asesor_1')?.clearValidators();
      this.form.get('asesor_2')?.clearValidators();
    } else if (tipo === 'ninguno') {
      this.form.get('asesor_interno')?.clearValidators();
      this.form.get('asesor_externo')?.clearValidators();
      this.form.get('director')?.clearValidators();
      this.form.get('asesor_1')?.clearValidators();
      this.form.get('asesor_2')?.clearValidators();
    } else {
      this.form.get('asesor_interno')?.clearValidators();
      this.form.get('asesor_externo')?.clearValidators();
      this.form.get('director')?.setValidators(required);
      this.form.get('asesor_1')?.setValidators(required);
      this.form.get('asesor_2')?.setValidators(required);
    }
    this.form.get('asesor_interno')?.updateValueAndValidity();
    this.form.get('asesor_externo')?.updateValueAndValidity();
    this.form.get('director')?.updateValueAndValidity();
    this.form.get('asesor_1')?.updateValueAndValidity();
    this.form.get('asesor_2')?.updateValueAndValidity();
  }

  onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    this.archivoSeleccionado = file ?? null;
    this.archivoRequeridoError = false;
    if (file) this.quitarArchivoSeleccionado = false;
  }

  /** Abre el picker nativo al hacer clic en cualquier parte del input de fecha. */
  abrirPicker(el: HTMLInputElement): void {
    try { el.showPicker(); } catch { /* navegadores sin soporte ignoran silenciosamente */ }
  }

  /** Indica si el control está inválido y ya fue tocado (para marcar en rojo y mostrar "Campo obligatorio"). */
  campoInvalido(controlName: string): boolean {
    const c = this.form.get(controlName);
    return !!(c?.invalid && c?.touched);
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
    this.archivoRequeridoError = false;
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    if (this.originalidadEstado === 'BLOQUEADO' || this.originalidadEstado === 'ADVERTENCIA') return;
    if (this.controlAltaEstado === 'BLOQUEADO') return;
    if (!this.editando && !this.archivoSeleccionado) {
      this.archivoRequeridoError = true;
      return;
    }
    const raw = this.form.getRawValue();
    const datos: EgresadoForm = {
      ...raw,
      curso_titulacion: raw.curso_titulacion ? 'si' : 'no',
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
