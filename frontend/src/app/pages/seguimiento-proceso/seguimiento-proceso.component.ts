import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import { ActivatedRoute, Router } from '@angular/router';
import { catchError, EMPTY, finalize, throwError, timeout } from 'rxjs';
import { AgendarActoComponent, ActoAgendadoDto } from './agendar-acto/agendar-acto.component';
import { HeaderComponent } from '../../layout/header/header.component';
import { mensajeErrorApiConBlob } from '../../core/http-blob-error';
import { EgresadoService, EgresadoDetail, EgresadoItem } from '../../services/egresado.service';
import { CatalogoService } from '../../services/catalogo.service';
import {
  calcularVistaPlazosNoResidencia,
  calcularVistaPlazosResidencia,
} from '../../core/plazos-titulacion-no-residencia';
import {
  calcularVistaPlazoDesarrolloRecepcionNoRes,
  construirPlazoDesarrolloRecepcionUi,
  type PlazoDesarrolloRecepcionUi,
} from '../../core/plazo-desarrollo-proyecto-no-res';

type EstadoFiltro = 'todos' | 'en_tiempo' | 'rezagado' | 'vencido' | 'concluido';
type OrdenFiltro = 'reciente' | 'prioridad' | 'nombre' | 'control';

/** Días de margen antes del límite para pasar de "en tiempo" a "rezagado". */
const MARGEN_REZAGO_DIAS = 30;

function inicioDiaLocal(d: Date): Date {
  return new Date(d.getFullYear(), d.getMonth(), d.getDate());
}

function diffDiasCalendario(fechaFin: Date, fechaInicio: Date): number {
  const ms = inicioDiaLocal(fechaFin).getTime() - inicioDiaLocal(fechaInicio).getTime();
  return Math.round(ms / 86400000);
}

function sumarMesesCalendario(base: Date, meses: number): Date {
  return new Date(base.getFullYear(), base.getMonth() + meses, base.getDate());
}

interface SeguimientoItem {
  id: string;
  alumno: string;
  noControl: string;
  carrera: string;
  /** Modalidad del proceso (valor de catálogo / formulario). */
  modalidad: string;
  estado: Exclude<EstadoFiltro, 'todos'>;
  documentoFaltante: string;
  ultimoMovimiento: string;
  ultimoMovimientoIso: string;
  fechaLimite: string;
}

type EstadoPaso = 'completado' | 'en_curso' | 'pendiente';

interface PasoTitulacionDef {
  key: string;
  titulo: string;
  descripcion: string;
}

interface PasoProcesoUi {
  numero: number;
  key: string;
  titulo: string;
  descripcion: string;
  fecha?: string;
  estado: EstadoPaso;
  /** Semáforo y fechas del plazo de desarrollo (paso recepción en división, flujo 16 no residencia). */
  plazoDesarrolloRecepcion?: PlazoDesarrolloRecepcionUi | null;
}

@Component({
  selector: 'app-seguimiento-proceso',
  standalone: true,
  imports: [CommonModule, FormsModule, HeaderComponent, AgendarActoComponent],
  templateUrl: './seguimiento-proceso.component.html',
  styleUrl: './seguimiento-proceso.component.css',
})
export class SeguimientoProcesoComponent implements OnInit, OnDestroy {
  cargando = true;
  error = '';
  items: SeguimientoItem[] = [];

  buscarControl = '';
  filtroCarrera = '';
  filtroModalidad = '';
  filtroDocumento = 'todos';
  filtroEstado: EstadoFiltro = 'todos';
  ordenarPor: OrdenFiltro = 'reciente';
  mostrarMasFiltros = false;
  detalleSeleccionado: EgresadoDetail | null = null;
  cargandoDetalle = false;
  procesandoPaso = false;
  mensajeProceso = '';
  observacionesReenvioDocEscaneada = '';
  cargandoDocEscaneada = false;
  /** Mensaje si falla la descarga del PDF (timeout, red, 401, etc.). */
  errorCargaDocEscaneada = '';
  vistaDocEscaneadaUrl: SafeResourceUrl | null = null;
  mostrarModalActo93 = false;
  /** Pasos del proceso: propiedad estable (no getter) para no destruir el DOM en cada ciclo de detección de cambios. */
  pasosProcesoTitulacionCache: PasoProcesoUi[] = [];
  private detalleRequestSeq = 0;
  private vistaDocEscaneadaObjectUrl: string | null = null;
  private refrescoListaIntervalo: ReturnType<typeof setInterval> | null = null;

  get carrerasDisponibles(): string[] {
    return [...new Set(this.items.map((i) => i.carrera))].sort((a, b) => a.localeCompare(b));
  }

  get modalidadesDisponibles(): string[] {
    const set = new Set<string>();
    for (const i of this.items) {
      const m = i.modalidad?.trim() ?? '';
      if (!m || m === '—') set.add('(Sin modalidad)');
      else set.add(m);
    }
    return [...set].sort((a, b) => a.localeCompare(b, 'es', { sensitivity: 'base' }));
  }

  get totalExpedientes(): number {
    return this.items.length;
  }

  get totalEnTiempo(): number {
    return this.items.filter((i) => i.estado === 'en_tiempo').length;
  }

  get totalRezagado(): number {
    return this.items.filter((i) => i.estado === 'rezagado').length;
  }

  get totalVencidos(): number {
    return this.items.filter((i) => i.estado === 'vencido').length;
  }

  get totalConcluidos(): number {
    return this.items.filter((i) => i.estado === 'concluido').length;
  }

  get itemsFiltrados(): SeguimientoItem[] {
    const term = this.buscarControl.trim().toLowerCase();
    let out = this.items.filter((i) => {
      if (term && !i.noControl.toLowerCase().includes(term)) return false;
      if (this.filtroCarrera && i.carrera !== this.filtroCarrera) return false;
      if (this.filtroModalidad) {
        const m = i.modalidad?.trim() ?? '';
        const etiqueta = !m || m === '—' ? '(Sin modalidad)' : m;
        if (etiqueta !== this.filtroModalidad) return false;
      }
      if (this.filtroDocumento !== 'todos' && i.documentoFaltante !== this.filtroDocumento) return false;
      if (this.filtroEstado !== 'todos' && i.estado !== this.filtroEstado) return false;
      return true;
    });

    if (this.ordenarPor === 'nombre') {
      out = out.sort((a, b) => a.alumno.localeCompare(b.alumno));
    } else if (this.ordenarPor === 'control') {
      out = out.sort((a, b) => a.noControl.localeCompare(b.noControl));
    } else if (this.ordenarPor === 'reciente') {
      out = out.sort((a, b) => b.ultimoMovimientoIso.localeCompare(a.ultimoMovimientoIso));
    } else {
      const prioridad = { vencido: 0, rezagado: 1, en_tiempo: 2, concluido: 3 };
      out = out.sort((a, b) => {
        const porEstado = prioridad[a.estado] - prioridad[b.estado];
        if (porEstado !== 0) return porEstado;
        return b.ultimoMovimientoIso.localeCompare(a.ultimoMovimientoIso);
      });
    }
    return out;
  }

  constructor(
    private egresadoService: EgresadoService,
    private router: Router,
    private route: ActivatedRoute,
    private catalogoService: CatalogoService,
    private sanitizer: DomSanitizer,
  ) {}

  ngOnInit(): void {
    const buscar = this.route.snapshot.queryParamMap.get('buscar')?.trim();
    if (buscar) this.buscarControl = buscar;
    this.cargar();
    this.refrescoListaIntervalo = setInterval(() => this.cargar(true), 45000);
  }

  ngOnDestroy(): void {
    if (this.refrescoListaIntervalo) {
      clearInterval(this.refrescoListaIntervalo);
      this.refrescoListaIntervalo = null;
    }
    this.limpiarVistaDocEscaneada();
  }

  seleccionarEstado(estado: EstadoFiltro): void {
    this.filtroEstado = estado;
  }

  volverInicio(): void {
    this.router.navigate(['/home']);
  }

  esEstadoActivo(estado: EstadoFiltro): boolean {
    return this.filtroEstado === estado;
  }

  limpiarFiltros(): void {
    this.buscarControl = '';
    this.filtroCarrera = '';
    this.filtroModalidad = '';
    this.filtroDocumento = 'todos';
    this.filtroEstado = 'todos';
    this.ordenarPor = 'reciente';
    this.mostrarMasFiltros = false;
  }

  badgeEstado(estado: SeguimientoItem['estado']): string {
    if (estado === 'vencido') return 'Vencido';
    if (estado === 'rezagado') return 'Rezagado';
    if (estado === 'concluido') return 'Concluido';
    return 'En tiempo';
  }

  /** Misma modalidad que en formulario / backend (Residencia Profesional usa Liberar; el resto revisión académica). */
  get esResidenciaProfesionalSeguimiento(): boolean {
    const m = (this.detalleSeleccionado?.datos_proyecto?.modalidad ?? '').trim();
    return this.catalogoService.esResidencia(m);
  }

  get esCenevalSeguimiento(): boolean {
    const m = (this.detalleSeleccionado?.datos_proyecto?.modalidad ?? '').trim();
    return this.catalogoService.esCeneval(m);
  }

  /** Todos los flujos con acto 9.3 exigen confirmar entrega antes de documentación escaneada. */
  get requiereEntregaAnexo93AntesDocEscaneada(): boolean {
    return true;
  }

  prerequisitoSinodalesCumplido(d: EgresadoDetail): boolean {
    return !!d.fecha_confirmacion_recibido_anexo_9_2?.trim();
  }

  /** Paso 2 CENEVAL / residencia (anexos): paso 1 CENEVAL confirmado. */
  prerequisitoCrearAnexo91(d: EgresadoDetail): boolean {
    if (this.esCenevalSeguimiento) {
      return !!d.fecha_confirmacion_entrega_egresado_depto?.trim();
    }
    return !!d.fecha_confirmacion_recibidos_anexo_xxxi_xxxii?.trim();
  }

  seleccionarEgresado(item: SeguimientoItem): void {
    if (this.procesandoPaso) {
      this.mensajeProceso = 'Espera a que termine la acción en curso (por ejemplo agendar o crear anexo).';
      return;
    }
    this.mensajeProceso = '';
    this.cargandoDetalle = true;
    this.detalleSeleccionado = null;
    this.pasosProcesoTitulacionCache = [];
    this.observacionesReenvioDocEscaneada = '';
    this.limpiarVistaDocEscaneada();
    this.mostrarModalActo93 = false;
    const requestSeq = ++this.detalleRequestSeq;
    const guard = window.setTimeout(() => {
      if (requestSeq === this.detalleRequestSeq && this.cargandoDetalle) {
        this.cargandoDetalle = false;
        this.mensajeProceso = 'No se pudo cargar el detalle a tiempo. Intenta de nuevo.';
      }
    }, 22000);
    this.egresadoService
      .obtenerPorId(item.id, false)
      .pipe(
        timeout(20000),
        // Solo usamos respaldo por numero_control si el backend responde 404 al id.
        catchError((err) => {
          if (err instanceof HttpErrorResponse && err.status === 404 && item.noControl?.trim()) {
            return this.egresadoService.obtenerPorNumeroControl(item.noControl.trim()).pipe(timeout(20000));
          }
          return throwError(() => err);
        }),
        finalize(() => {
          clearTimeout(guard);
        }),
      )
      .subscribe({
        next: (d) => {
          if (requestSeq !== this.detalleRequestSeq) return;
          this.detalleSeleccionado = d;
          this.cargandoDetalle = false;
          this.actualizarPasosProcesoTitulacion({ scrollPasoActivo: true });
          this.cargarVistaDocumentacionEscaneada();
          this.sincronizarFilaListaConDetalle(d);
        },
        error: (err) => {
          if (requestSeq !== this.detalleRequestSeq) return;
          this.cargandoDetalle = false;
          this.mensajeProceso =
            err?.name === 'TimeoutError'
              ? 'El servidor tardó demasiado en responder al cargar el detalle.'
              : err?.error?.error ?? 'No se pudo cargar el detalle del egresado. Intenta de nuevo.';
        },
      });
  }

  trackByPasoNumero(_index: number, paso: PasoProcesoUi): string {
    return paso.key;
  }

  /** Expedientes con envío a CAT previo a la versión de 16 pasos (sin fecha de solicitud de anteproyecto). */
  get noResidenciaFlujoLegacy(): boolean {
    const d = this.detalleSeleccionado;
    if (!d || this.esResidenciaProfesionalSeguimiento || this.esCenevalSeguimiento) return false;
    return !!d.fecha_enviado_departamento_academico && !d.fecha_envio_solicitud_registro_anteproyecto_depto_academico;
  }

  /**
   * Plazo de desarrollo del proyecto vencido (BD o cómputo hasta solicitud de liberación en flujo 16).
   * Bloquea acciones del panel de proceso y se muestra en gris.
   */
  /** Último paso del seguimiento completado (o titulado en BD): sin reagendar/regenerar; solo descarga del PDF final. */
  get procesoConcluido(): boolean {
    const d = this.detalleSeleccionado;
    if (!d) return false;
    if (d.fecha_confirmacion_documentacion_escaneada_recibida) return true;
    return (d.estado_general || '').trim().toLowerCase() === 'titulado';
  }

  get procesoBloqueadoPorVencimientoPlazo(): boolean {
    const d = this.detalleSeleccionado;
    if (!d) return false;
    if (this.procesoConcluido) return false;
    if (d.estado_general === 'titulado') return false;
    if (d.estado_general === 'vencido') return true;
    if (this.esResidenciaProfesionalSeguimiento || this.noResidenciaFlujoLegacy || this.esCenevalSeguimiento) return false;
    const raw = calcularVistaPlazoDesarrolloRecepcionNoRes(d);
    return raw?.estado === 'vencido';
  }

  /**
   * Etiqueta EN TIEMPO / REZAGADO / VENCIDO junto al título del panel (mismo criterio que la tabla y el plazo de desarrollo).
   */
  get etiquetaPlazoTitulacionCabecera(): string {
    const d = this.detalleSeleccionado;
    if (!d) return '';
    if (this.procesoConcluido) return 'CONCLUIDO';
    if (d.estado_general === 'titulado') return '';
    if (d.estado_general === 'vencido') return 'VENCIDO';
    if (!this.esResidenciaProfesionalSeguimiento && !this.noResidenciaFlujoLegacy && !this.esCenevalSeguimiento) {
      const raw = calcularVistaPlazoDesarrolloRecepcionNoRes(d);
      if (raw) {
        if (raw.estado === 'vencido') return 'VENCIDO';
        if (raw.estado === 'rezagado') return 'REZAGADO';
        return 'EN TIEMPO';
      }
    }
    const row = this.items.find((i) => i.id === d.id);
    if (row) {
      if (row.estado === 'vencido') return 'VENCIDO';
      if (row.estado === 'rezagado') return 'REZAGADO';
      return 'EN TIEMPO';
    }
    return 'EN TIEMPO';
  }

  get claseBadgeEtiquetaPlazoTitulacion(): string {
    const e = this.etiquetaPlazoTitulacionCabecera;
    if (e === 'VENCIDO') return 'badge-bad';
    if (e === 'REZAGADO') return 'badge-mid';
    if (e === 'EN TIEMPO') return 'badge-ok';
    if (e === 'CONCLUIDO') return 'badge-concluido';
    return '';
  }

  /** Acciones del flujo (no aplica al botón de descarga final cuando está concluido). */
  get accionesProcesoBloqueadas(): boolean {
    return this.procesandoPaso || this.procesoBloqueadoPorVencimientoPlazo || this.procesoConcluido;
  }

  private actualizarPasosProcesoTitulacion(opciones?: { scrollPasoActivo?: boolean }): void {
    const scroll = opciones?.scrollPasoActivo === true;
    if (!this.detalleSeleccionado) {
      this.pasosProcesoTitulacionCache = [];
      return;
    }
    if (this.esResidenciaProfesionalSeguimiento) {
      this.pasosProcesoTitulacionCache = this.construirPasosSeguimientoResidencia();
      if (scroll) this.programarScrollAlPasoActual();
      return;
    }
    if (this.esCenevalSeguimiento) {
      this.pasosProcesoTitulacionCache = this.construirPasosSeguimientoCeneval();
      if (scroll) this.programarScrollAlPasoActual();
      return;
    }
    if (this.noResidenciaFlujoLegacy) {
      this.pasosProcesoTitulacionCache = this.construirPasosSeguimientoNoResidenciaLegacy();
    } else {
      this.pasosProcesoTitulacionCache = this.construirPasosSeguimientoNoResidencia16();
    }
    if (scroll) this.programarScrollAlPasoActual();
  }

  /** Tras pintar la lista, desplaza el panel al paso en curso (o al primer pendiente). */
  private programarScrollAlPasoActual(): void {
    if (!this.pasosProcesoTitulacionCache.length) return;
    setTimeout(() => {
      requestAnimationFrame(() => this.scrollAlPasoActualEnLista());
    }, 0);
  }

  private scrollAlPasoActualEnLista(): void {
    const pasos = this.pasosProcesoTitulacionCache;
    let target = pasos.find((p) => p.estado === 'en_curso');
    if (!target) target = pasos.find((p) => p.estado === 'pendiente');
    if (!target) target = pasos[pasos.length - 1];
    const el = document.getElementById(`seg-paso-${target.key}`);
    el?.scrollIntoView({ behavior: 'smooth', block: 'center', inline: 'nearest' });
  }

  private pasosTitulacionCompartidosDef(): PasoTitulacionDef[] {
    return [
      {
        key: 'fecha_creacion_anexo_9_1',
        titulo: 'Generar anexo 9.1 (formato de solicitud del acto de recepción profesional)',
        descripcion: 'Se genera el documento correspondiente en el sistema.',
      },
      {
        key: 'fecha_confirmacion_entrega_anexo_9_1',
        titulo: 'Entrega de anexo 9.1 al sustentante',
        descripcion: 'Se confirma la entrega del anexo al sustentante.',
      },
      {
        key: 'fecha_solicitud_anexo_9_2',
        titulo:
          'Solicitar anexo 9.2 al sustentante (constancia de no inconveniencia para su acto de recepción profesional)',
        descripcion: 'La DEP solicita al sustentante la constancia 9.2; el trámite queda pendiente en Servicios escolares.',
      },
      {
        key: 'fecha_aceptacion_servicios_escolares_anexo_9_2',
        titulo: 'Servicios escolares generó el anexo 9.2',
        descripcion: 'Servicios escolares confirma en su bandeja que generó la constancia 9.2.',
      },
      {
        key: 'fecha_confirmacion_recibido_anexo_9_2',
        titulo: 'Recibe la DEP el anexo 9.2',
        descripcion: 'La DEP registra la recepción de la constancia 9.2.',
      },
      {
        key: 'fecha_solicitud_sinodales',
        titulo: 'Solicita sinodales la DEP al departamento académico',
        descripcion: 'La DEP envía la solicitud de asignación de sinodales.',
      },
      {
        key: 'fecha_confirmacion_sinodales_recibidos',
        titulo: 'Entrega oficio de asignación de sinodales el departamento académico a la DEP',
        descripcion:
          'El departamento académico registra la asignación; la DEP confirma con «Confirmar» la recepción del oficio.',
      },
      {
        key: 'fecha_agenda_acto_9_3',
        titulo: 'La DEP agenda fecha y horario para la realización del acto protocolario del sustentante',
        descripcion: 'Se agenda día y hora del acto dentro de la ventana permitida (lunes a viernes, 9:00–14:00).',
      },
      {
        key: 'fecha_creacion_anexo_9_3',
        titulo:
          'La DEP genera el anexo 9.3 (aviso de realización de acto protocolario de titulación integral)',
        descripcion: 'Se genera el PDF del anexo 9.3 después del agendamiento.',
      },
    ];
  }

  private pasosDocumentacionEscaneadaDef(): PasoTitulacionDef[] {
    return [
      {
        key: 'fecha_solicitud_documentacion_escaneada',
        titulo: 'Entrega de documentación escaneada del proceso correspondiente a la titulación integral',
        descripcion: 'La DEP solicita al sustentante que suba en el sistema los PDF de su proceso.',
      },
      {
        key: 'fecha_confirmacion_documentacion_escaneada_recibida',
        titulo: 'Se recibió documentación correspondiente a la titulación integral',
        descripcion: 'La DEP confirma la recepción de los documentos escaneados enviados por el sustentante.',
      },
    ];
  }

  private mapearDefsAUiPasos(steps: PasoTitulacionDef[]): PasoProcesoUi[] {
    const d = this.detalleSeleccionado!;
    let todosPreviosCompletados = true;
    return steps.map((s, i) => {
      if (s.key === 'fecha_solicitud_documentacion_escaneada') {
        const fecha = d.fecha_solicitud_documentacion_escaneada;
        const completado = !!fecha;
        const estado: EstadoPaso = completado ? 'completado' : todosPreviosCompletados ? 'en_curso' : 'pendiente';
        if (!completado) todosPreviosCompletados = false;
        return { numero: i + 1, key: s.key, titulo: s.titulo, descripcion: s.descripcion, fecha, estado };
      }
      if (s.key === 'fecha_confirmacion_documentacion_escaneada_recibida') {
        const fechaConf = d.fecha_confirmacion_documentacion_escaneada_recibida;
        const fechaEnv = d.fecha_envio_documentacion_escaneada_egresado;
        const completado = !!fechaConf;
        let estado: EstadoPaso;
        if (completado) estado = 'completado';
        else if (!todosPreviosCompletados) estado = 'pendiente';
        else if (fechaEnv) estado = 'en_curso';
        else estado = 'pendiente';
        const fecha = fechaConf || fechaEnv;
        if (!completado) todosPreviosCompletados = false;
        return { numero: i + 1, key: s.key, titulo: s.titulo, descripcion: s.descripcion, fecha, estado };
      }
      const fecha = (d as unknown as Record<string, string | undefined>)[s.key];
      const completado = !!fecha;
      const estado: EstadoPaso = completado ? 'completado' : todosPreviosCompletados ? 'en_curso' : 'pendiente';
      if (!completado) todosPreviosCompletados = false;
      return { numero: i + 1, key: s.key, titulo: s.titulo, descripcion: s.descripcion, fecha, estado };
    });
  }

  private construirPasosSeguimientoResidencia(): PasoProcesoUi[] {
    const modalidad = (this.detalleSeleccionado?.datos_proyecto?.modalidad ?? '').trim() || '—';
    const pasosInicio: PasoTitulacionDef[] = [
      {
        key: 'fecha_enviado_departamento_academico',
        titulo: `Enviar solicitud para registro y liberación de proyecto de titulación integral al departamento académico, con la modalidad de ${modalidad}`,
        descripcion: 'La DEP registra el envío de la solicitud al departamento académico.',
      },
      {
        key: 'fecha_confirmacion_recibidos_anexo_xxxi_xxxii',
        titulo:
          'Recibimos anexos XXXII y XXXIII (registro y liberación) del proyecto de titulación integral por parte del departamento académico',
        descripcion: 'La DEP confirma la recepción de los documentos del departamento académico.',
      },
    ];
    const pasoEntrega93: PasoTitulacionDef[] = [
      {
        key: 'fecha_confirmacion_entrega_anexo_9_3',
        titulo: 'Entrega de anexo 9.3 a sinodales y sustentante',
        descripcion: 'La DEP confirma la entrega del aviso al jurado y al sustentante.',
      },
    ];
    const steps = [...pasosInicio, ...this.pasosTitulacionCompartidosDef(), ...pasoEntrega93, ...this.pasosDocumentacionEscaneadaDef()];
    return this.mapearDefsAUiPasos(steps);
  }

  private pasosSinodalesDef(): PasoTitulacionDef[] {
    return this.pasosTitulacionCompartidosDef().filter(
      (s) => s.key === 'fecha_solicitud_sinodales' || s.key === 'fecha_confirmacion_sinodales_recibidos',
    );
  }

  private pasosPostSinodalesResidenciaDef(): PasoTitulacionDef[] {
    return this.pasosTitulacionCompartidosDef().filter(
      (s) => s.key === 'fecha_agenda_acto_9_3' || s.key === 'fecha_creacion_anexo_9_3',
    );
  }

  private construirPasosSeguimientoCeneval(): PasoProcesoUi[] {
    const pasosInicio: PasoTitulacionDef[] = [
      {
        key: 'fecha_confirmacion_entrega_egresado_depto',
        titulo: 'Recibimos solicitud y copia del testimonio por la opción de examen CENEVAL',
        descripcion: 'La DEP confirma la recepción de la solicitud y la copia del testimonio.',
      },
    ];
    const pasoEntrega93: PasoTitulacionDef[] = [
      {
        key: 'fecha_confirmacion_entrega_anexo_9_3',
        titulo: 'Entrega de anexo 9.3 a sinodales y sustentante',
        descripcion: 'La DEP confirma la entrega del aviso al jurado y al sustentante.',
      },
    ];
    const steps = [
      ...pasosInicio,
      ...this.pasosTitulacionCompartidosDef(),
      ...pasoEntrega93,
      ...this.pasosDocumentacionEscaneadaDef(),
    ];
    return this.mapearDefsAUiPasos(steps);
  }

  private construirPasosSeguimientoNoResidenciaLegacy(): PasoProcesoUi[] {
    const pasosInicio: PasoTitulacionDef[] = [
      {
        key: 'fecha_enviado_departamento_academico',
        titulo:
          'La DEP envía la solicitud de registro, revisión y aprobación del proyecto de titulación integral al Departamento de Apoyo a la Titulación.',
        descripcion: 'La DEP registra el envío de la solicitud al departamento académico.',
      },
      {
        key: 'fecha_confirmacion_recibidos_anexo_xxxi_xxxii',
        titulo:
          'La DEP recibe los anexos XXXII y XXXIII (registro y aprobación) del proyecto de titulación integral por parte del Departamento de Apoyo a la Titulación.',
        descripcion: 'La DEP confirma la recepción de los documentos del departamento académico.',
      },
    ];
    const steps = [...pasosInicio, ...this.pasosTitulacionCompartidosDef(), ...this.pasoEntrega93Def(), ...this.pasosDocumentacionEscaneadaDef()];
    return this.mapearDefsAUiPasos(steps);
  }

  private pasoEntrega93Def(): PasoTitulacionDef[] {
    return [
      {
        key: 'fecha_confirmacion_entrega_anexo_9_3',
        titulo: 'Entrega de anexo 9.3 a sinodales y sustentante',
        descripcion: 'La DEP confirma la entrega del aviso al jurado y al sustentante.',
      },
    ];
  }

  /** Paso 1 completado (retrocompat: expedientes previos al campo nuevo). */
  entregaEgresadoDeptoCompleta(d: EgresadoDetail): boolean {
    return !!(
      d.fecha_confirmacion_entrega_egresado_depto?.trim() ||
      d.fecha_envio_solicitud_registro_anteproyecto_depto_academico?.trim()
    );
  }

  private construirPasosSeguimientoNoResidencia16(): PasoProcesoUi[] {
    const d = this.detalleSeleccionado!;
    const modalidadTitulo = (d.datos_proyecto?.modalidad ?? '').trim() || 'Tesis';
    const defs: PasoTitulacionDef[] = [
      {
        key: 'fecha_confirmacion_entrega_egresado_depto',
        titulo:
          'Entrega del egresado a la DEP: solicitud de inicio de proceso de titulación (anexo XXXI) y anteproyecto',
        descripcion: 'La DEP confirma que recibió del egresado la solicitud de inicio y el anteproyecto.',
      },
      {
        key: 'fecha_envio_solicitud_registro_anteproyecto_depto_academico',
        titulo:
          `La DEP envía al departamento académico el anteproyecto y solicita el registro de ${modalidadTitulo} (anexo XXXII)`,
        descripcion:
          'La DEP registra el envío al departamento académico; en la bandeja Anteproyecto quedará pendiente de marcar como registrado.',
      },
      {
        key: 'fecha_confirmacion_recepcion_inicial_anexos_xxxi_xxxii',
        titulo:
          `La DEP recibe el registro de ${modalidadTitulo} (Anexo XXXII) por parte del departamento académico`,
        descripcion:
          'La DEP confirma la recepción del registro una vez que el departamento académico marcó como registrado.',
      },
      {
        key: 'fecha_recepcion_trabajo_division_estudios_prof',
        titulo: `El egresado está desarrollando su proyecto de ${modalidadTitulo}`,
        descripcion: '',
      },
      {
        key: 'fecha_solicitud_registro_liberacion_depto_academico',
        titulo: 'Liberación de producto en el departamento académico',
        descripcion:
          `El departamento académico sube ${modalidadTitulo} en la pestaña Liberación de producto y pulsa Liberar (por carrera).`,
      },
      {
        key: 'fecha_recepcion_registro_liberacion_depto_academico',
        titulo: `La DEP recibe la liberación de ${modalidadTitulo} (Anexo XXXIII)`,
        descripcion: `La DEP confirma la recepción de la liberación de ${modalidadTitulo}.`,
      },
      {
        key: 'fecha_enviado_departamento_academico',
        titulo:
          'Envío a Coordinación de Apoyo a la Titulación para la revisión de acuerdo a las normas de presentación para trabajos profesionales',
        descripcion:
          `La DEP envía ${modalidadTitulo} a Coordinación de Apoyo a la Titulación para la revisión según las normas de presentación para trabajos profesionales.`,
      },
      ...this.pasosTitulacionCompartidosDef(),
      ...this.pasoEntrega93Def(),
      ...this.pasosDocumentacionEscaneadaDef(),
    ];
    let todosPreviosCompletados = true;
    return defs.map((s, i) => {
      if (s.key === 'fecha_confirmacion_entrega_egresado_depto') {
        const fecha = d.fecha_confirmacion_entrega_egresado_depto;
        const completado = this.entregaEgresadoDeptoCompleta(d);
        const estado: EstadoPaso = completado ? 'completado' : todosPreviosCompletados ? 'en_curso' : 'pendiente';
        if (!completado) todosPreviosCompletados = false;
        return {
          numero: i + 1,
          key: s.key,
          titulo: s.titulo,
          descripcion: s.descripcion,
          fecha: fecha || d.fecha_envio_solicitud_registro_anteproyecto_depto_academico,
          estado,
        };
      }
      if (s.key === 'fecha_envio_solicitud_registro_anteproyecto_depto_academico') {
        const fecha = d.fecha_envio_solicitud_registro_anteproyecto_depto_academico;
        const completado = !!fecha;
        let estado: EstadoPaso;
        if (completado) estado = 'completado';
        else if (!this.entregaEgresadoDeptoCompleta(d) || !todosPreviosCompletados) estado = 'pendiente';
        else estado = 'en_curso';
        if (!completado) todosPreviosCompletados = false;
        return { numero: i + 1, key: s.key, titulo: s.titulo, descripcion: s.descripcion, fecha, estado };
      }
      if (s.key === 'fecha_confirmacion_recepcion_inicial_anexos_xxxi_xxxii') {
        const fecha = d.fecha_confirmacion_recepcion_inicial_anexos_xxxi_xxxii;
        const completado = !!fecha;
        const paso1Completo = !!d.fecha_envio_solicitud_registro_anteproyecto_depto_academico;
        const registradoAcademico = !!d.fecha_registrado_departamento;
        let estado: EstadoPaso;
        if (completado) estado = 'completado';
        else if (!paso1Completo || !todosPreviosCompletados) estado = 'pendiente';
        else if (!registradoAcademico) estado = 'pendiente';
        else estado = 'en_curso';
        if (!completado) todosPreviosCompletados = false;
        return {
          numero: i + 1,
          key: s.key,
          titulo: s.titulo,
          descripcion: s.descripcion,
          fecha,
          estado,
        };
      }
      if (s.key === 'fecha_recepcion_trabajo_division_estudios_prof') {
        const inicial = !!d.fecha_confirmacion_recepcion_inicial_anexos_xxxi_xxxii;
        const liberacion = d.fecha_solicitud_registro_liberacion_depto_academico;
        const completado = !!liberacion;
        let estado: EstadoPaso;
        if (completado) estado = 'completado';
        else if (!inicial || !todosPreviosCompletados) estado = 'pendiente';
        else estado = 'en_curso';
        if (!completado) todosPreviosCompletados = false;
        const plazoDesarrolloRecepcion =
          estado === 'en_curso' ? construirPlazoDesarrolloRecepcionUi(d) ?? undefined : undefined;
        return {
          numero: i + 1,
          key: s.key,
          titulo: `El egresado está desarrollando su proyecto de ${modalidadTitulo}.`,
          descripcion: '',
          fecha: liberacion || undefined,
          estado,
          plazoDesarrolloRecepcion,
        };
      }
      if (s.key === 'fecha_solicitud_registro_liberacion_depto_academico') {
        const inicial = !!d.fecha_confirmacion_recepcion_inicial_anexos_xxxi_xxxii;
        const fecha = d.fecha_solicitud_registro_liberacion_depto_academico;
        const completado = !!fecha;
        let estado: EstadoPaso;
        if (completado) estado = 'completado';
        else if (!inicial || !todosPreviosCompletados) estado = 'pendiente';
        else estado = 'en_curso';
        if (!completado) todosPreviosCompletados = false;
        return {
          numero: i + 1,
          key: s.key,
          titulo: s.titulo,
          descripcion: s.descripcion,
          fecha,
          estado,
        };
      }
      const numeroPaso = i + 1;
      const tituloPaso = s.titulo;
      if (s.key === 'fecha_solicitud_documentacion_escaneada') {
        const fecha = d.fecha_solicitud_documentacion_escaneada;
        const completado = !!fecha;
        const estado: EstadoPaso = completado ? 'completado' : todosPreviosCompletados ? 'en_curso' : 'pendiente';
        if (!completado) todosPreviosCompletados = false;
        return {
          numero: numeroPaso,
          key: s.key,
          titulo: tituloPaso,
          descripcion: s.descripcion,
          fecha,
          estado,
        };
      }
      if (s.key === 'fecha_confirmacion_documentacion_escaneada_recibida') {
        const fechaConf = d.fecha_confirmacion_documentacion_escaneada_recibida;
        const fechaEnv = d.fecha_envio_documentacion_escaneada_egresado;
        const completado = !!fechaConf;
        let estado: EstadoPaso;
        if (completado) estado = 'completado';
        else if (!todosPreviosCompletados) estado = 'pendiente';
        else if (fechaEnv) estado = 'en_curso';
        else estado = 'pendiente';
        const fecha = fechaConf || fechaEnv;
        if (!completado) todosPreviosCompletados = false;
        return {
          numero: numeroPaso,
          key: s.key,
          titulo: tituloPaso,
          descripcion: s.descripcion,
          fecha,
          estado,
        };
      }
      const fecha = (d as unknown as Record<string, string | undefined>)[s.key];
      const completado = !!fecha;
      const estado: EstadoPaso = completado ? 'completado' : todosPreviosCompletados ? 'en_curso' : 'pendiente';
      if (!completado) todosPreviosCompletados = false;
      return {
        numero: numeroPaso,
        key: s.key,
        titulo: tituloPaso,
        descripcion: s.descripcion,
        fecha,
        estado,
      };
    });
  }

  etiquetaEstadoPaso(estado: EstadoPaso): string {
    if (estado === 'completado') return 'Completado';
    if (estado === 'en_curso') return 'Paso actual';
    return 'Pendiente';
  }

  etiquetaEstadoPasoPara(paso: PasoProcesoUi): string {
    const d = this.detalleSeleccionado;
    if (
      paso.key === 'fecha_confirmacion_documentacion_escaneada_recibida' &&
      paso.estado === 'pendiente' &&
      d?.fecha_solicitud_documentacion_escaneada &&
      !d?.fecha_envio_documentacion_escaneada_egresado
    ) {
      return 'En espera del egresado';
    }
    return this.etiquetaEstadoPaso(paso.estado);
  }

  formatearFechaHora(iso?: string): string {
    if (!iso) return '';
    const d = new Date(iso);
    if (isNaN(d.getTime())) return iso;
    const dia = d.getDate().toString().padStart(2, '0');
    const mes = (d.getMonth() + 1).toString().padStart(2, '0');
    const anio = d.getFullYear();
    const h = d.getHours().toString().padStart(2, '0');
    const m = d.getMinutes().toString().padStart(2, '0');
    return `${dia}/${mes}/${anio}, ${h}:${m}`;
  }

  private refrescarDetalle(): void {
    if (!this.detalleSeleccionado) return;
    const id = this.detalleSeleccionado.id;
    this.egresadoService
      .obtenerPorId(id, false, true)
      .pipe(
        timeout(25000),
        catchError((err) => {
          const extra =
            err?.name === 'TimeoutError'
              ? 'El servidor tardó al refrescar el detalle.'
              : 'No se pudo refrescar el detalle.';
          this.mensajeProceso = this.mensajeProceso ? `${this.mensajeProceso} ${extra}` : extra;
          return EMPTY;
        }),
      )
      .subscribe({
        next: (d) => {
          if (this.detalleSeleccionado?.id === id) {
            this.detalleSeleccionado = d;
            this.actualizarPasosProcesoTitulacion();
            this.cargarVistaDocumentacionEscaneada();
            this.sincronizarFilaListaConDetalle(d);
          }
        },
      });
  }

  private limpiarVistaDocEscaneada(): void {
    if (this.vistaDocEscaneadaObjectUrl) {
      URL.revokeObjectURL(this.vistaDocEscaneadaObjectUrl);
      this.vistaDocEscaneadaObjectUrl = null;
    }
    this.vistaDocEscaneadaUrl = null;
    this.cargandoDocEscaneada = false;
    this.errorCargaDocEscaneada = '';
  }

  private cargarVistaDocumentacionEscaneada(): void {
    const d = this.detalleSeleccionado;
    this.observacionesReenvioDocEscaneada = '';
    this.limpiarVistaDocEscaneada();
    if (!d?.fecha_envio_documentacion_escaneada_egresado) return;
    this.cargandoDocEscaneada = true;
    this.errorCargaDocEscaneada = '';
    this.egresadoService
      .getDocumentacionEscaneada(d.id)
      .pipe(
        timeout(120000),
        catchError((err) => {
          this.errorCargaDocEscaneada =
            err?.name === 'TimeoutError'
              ? 'El servidor tardó demasiado en enviar el PDF. Revisa la conexión o intenta de nuevo.'
              : 'No se pudo obtener el PDF. Vuelve a iniciar sesión o verifica que el archivo exista en el servidor.';
          return EMPTY;
        }),
        finalize(() => {
          this.cargandoDocEscaneada = false;
        }),
      )
      .subscribe({
        next: ({ blob }) => {
          if (!blob?.size) {
            this.errorCargaDocEscaneada = 'El servidor respondió sin contenido del PDF.';
            return;
          }
          this.vistaDocEscaneadaObjectUrl = URL.createObjectURL(blob);
          this.vistaDocEscaneadaUrl = this.sanitizer.bypassSecurityTrustResourceUrl(this.vistaDocEscaneadaObjectUrl);
        },
      });
  }

  private sincronizarFilaListaConDetalle(d: EgresadoDetail): void {
    const row = this.items.find((i) => i.id === d.id);
    if (!row) return;
    if (d.fecha_confirmacion_documentacion_escaneada_recibida) {
      row.estado = 'concluido';
      row.documentoFaltante = 'Proceso concluido';
      row.fechaLimite = 'Finalizado';
    }
  }

  private descargarBlob(blob: Blob, nombreArchivo: string): void {
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = nombreArchivo;
    a.click();
    URL.revokeObjectURL(url);
  }

  enviarDepartamento(): void {
    if (!this.detalleSeleccionado || this.procesandoPaso || this.detalleSeleccionado.fecha_enviado_departamento_academico) return;
    this.procesandoPaso = true;
    this.mensajeProceso = '';
    this.egresadoService.enviarDepartamentoAcademico(this.detalleSeleccionado.id).subscribe({
      next: () => {
        this.procesandoPaso = false;
        this.mensajeProceso = 'Solicitud enviada al Departamento de Apoyo a la Titulación.';
        this.refrescarDetalle();
      },
      error: (err) => {
        this.procesandoPaso = false;
        this.mensajeProceso = err?.error?.error ?? 'No se pudo enviar.';
      },
    });
  }

  solicitarRegistroAnteproyectoNoResidencia(): void {
    if (!this.detalleSeleccionado || this.procesandoPaso) return;
    this.procesandoPaso = true;
    this.mensajeProceso = '';
    this.egresadoService.solicitarRegistroAnteproyectoNoResidencia(this.detalleSeleccionado.id).subscribe({
      next: () => {
        this.procesandoPaso = false;
        const d = this.detalleSeleccionado;
        const depto =
          d?.nombre_departamento_academico?.trim() ||
          this.catalogoService.nombreDepartamentoPorCarreraSync(d?.datos_personales?.carrera ?? '') ||
          'el departamento académico de su carrera';
        this.mensajeProceso = `Envío registrado. El egresado debe aparecer en Anteproyecto de «${depto}» para marcar como registrado.`;
        this.refrescarDetalle();
      },
      error: (err) => {
        this.procesandoPaso = false;
        this.mensajeProceso = err?.error?.error ?? 'No se pudo registrar el envío.';
      },
    });
  }

  confirmarRecepcionInicialAnexosNoResidencia(): void {
    if (!this.detalleSeleccionado || this.procesandoPaso) return;
    this.procesandoPaso = true;
    this.mensajeProceso = '';
    this.egresadoService.confirmarRecepcionInicialAnexosXxxiXxxiiNoResidencia(this.detalleSeleccionado.id).subscribe({
      next: () => {
        this.procesandoPaso = false;
        this.mensajeProceso = 'Recepción de anexos XXXI y XXXII confirmada.';
        this.refrescarDetalle();
      },
      error: (err) => {
        this.procesandoPaso = false;
        this.mensajeProceso = err?.error?.error ?? 'No se pudo confirmar la recepción inicial.';
      },
    });
  }

  confirmarEntregaEgresadoDeptoNoResidencia(): void {
    if (!this.detalleSeleccionado || this.procesandoPaso) return;
    this.procesandoPaso = true;
    this.mensajeProceso = '';
    this.egresadoService.confirmarEntregaEgresadoDeptoNoResidencia(this.detalleSeleccionado.id).subscribe({
      next: () => {
        this.procesandoPaso = false;
        this.mensajeProceso = 'Entrega del egresado confirmada.';
        this.refrescarDetalle();
      },
      error: (err) => {
        this.procesandoPaso = false;
        this.mensajeProceso = err?.error?.error ?? 'No se pudo confirmar.';
      },
    });
  }

  confirmarRecepcionTrabajoNoResidencia(): void {
    if (!this.detalleSeleccionado || this.procesandoPaso) return;
    this.procesandoPaso = true;
    this.mensajeProceso = '';
    this.egresadoService.confirmarRecepcionTrabajoNoResidencia(this.detalleSeleccionado.id).subscribe({
      next: () => {
        this.procesandoPaso = false;
        this.mensajeProceso = 'Recepción en división de estudios confirmada.';
        this.refrescarDetalle();
      },
      error: (err) => {
        this.procesandoPaso = false;
        this.mensajeProceso = err?.error?.error ?? 'No se pudo confirmar.';
      },
    });
  }

  confirmarRecepcionRegistroLiberacionNoResidencia(): void {
    if (!this.detalleSeleccionado || this.procesandoPaso) return;
    this.procesandoPaso = true;
    this.mensajeProceso = '';
    this.egresadoService.confirmarRecepcionRegistroLiberacionNoResidencia(this.detalleSeleccionado.id).subscribe({
      next: () => {
        this.procesandoPaso = false;
        this.mensajeProceso = 'Recepción de liberación confirmada.';
        this.refrescarDetalle();
      },
      error: (err) => {
        this.procesandoPaso = false;
        this.mensajeProceso = err?.error?.error ?? 'No se pudo confirmar la recepción.';
      },
    });
  }

  confirmarRecibidosAnexos(): void {
    if (!this.detalleSeleccionado || this.procesandoPaso) return;
    this.procesandoPaso = true;
    this.mensajeProceso = '';
    this.egresadoService.confirmarRecibidosAnexosXxxiXxxii(this.detalleSeleccionado.id).subscribe({
      next: () => {
        this.procesandoPaso = false;
        this.mensajeProceso = 'Recibidos XXXII/XXXIII confirmados.';
        this.refrescarDetalle();
      },
      error: (err) => {
        this.procesandoPaso = false;
        this.mensajeProceso = err?.error?.error ?? 'No se pudo confirmar.';
      },
    });
  }

  crearDescargar91(): void {
    if (!this.detalleSeleccionado || this.procesandoPaso) return;
    this.procesandoPaso = true;
    this.mensajeProceso = '';
    const nc = this.detalleSeleccionado.numero_control;
    this.egresadoService.descargarAnexo91(this.detalleSeleccionado.id).subscribe({
      next: (blob) => {
        this.procesandoPaso = false;
        this.descargarBlob(blob, `Anexo-9.1-${nc}.pdf`);
        this.mensajeProceso = 'Anexo 9.1 generado.';
        this.refrescarDetalle();
      },
      error: (err) => {
        this.procesandoPaso = false;
        void mensajeErrorApiConBlob(err, 'No se pudo generar 9.1.').then((m) => {
          this.mensajeProceso = m;
        });
      },
    });
  }

  confirmarEntrega91(): void {
    if (!this.detalleSeleccionado || this.procesandoPaso) return;
    this.procesandoPaso = true;
    this.mensajeProceso = '';
    this.egresadoService.confirmarEntregaAnexo91(this.detalleSeleccionado.id).subscribe({
      next: () => {
        this.procesandoPaso = false;
        this.mensajeProceso = 'Entrega 9.1 confirmada.';
        this.refrescarDetalle();
      },
      error: (err) => {
        this.procesandoPaso = false;
        this.mensajeProceso = err?.error?.error ?? 'No se pudo confirmar entrega.';
      },
    });
  }

  solicitarConstancia92AlEgresado(): void {
    if (!this.detalleSeleccionado || this.procesandoPaso) return;
    this.procesandoPaso = true;
    this.mensajeProceso = '';
    this.egresadoService.solicitarConstancia92Division(this.detalleSeleccionado.id).subscribe({
      next: () => {
        this.procesandoPaso = false;
        this.mensajeProceso = 'Solicitud de constancia 9.2 registrada.';
        this.refrescarDetalle();
      },
      error: (err) => {
        this.procesandoPaso = false;
        this.mensajeProceso = err?.error?.error ?? 'No se pudo registrar la solicitud 9.2.';
      },
    });
  }

  confirmarRecibido92(): void {
    if (!this.detalleSeleccionado || this.procesandoPaso) return;
    this.procesandoPaso = true;
    this.mensajeProceso = '';
    this.egresadoService.confirmarRecibidoAnexo92(this.detalleSeleccionado.id).subscribe({
      next: () => {
        this.procesandoPaso = false;
        this.mensajeProceso = 'Constancia 9.2: recepción confirmada.';
        this.refrescarDetalle();
      },
      error: (err) => {
        this.procesandoPaso = false;
        this.mensajeProceso = err?.error?.error ?? 'No se pudo confirmar la recepción del 9.2.';
      },
    });
  }

  solicitarSinodales(): void {
    if (!this.detalleSeleccionado || this.procesandoPaso) return;
    this.procesandoPaso = true;
    this.mensajeProceso = '';
    this.egresadoService.solicitarSinodales(this.detalleSeleccionado.id).subscribe({
      next: () => {
        this.procesandoPaso = false;
        this.mensajeProceso = 'Solicitud de sinodales enviada.';
        this.refrescarDetalle();
      },
      error: (err) => {
        this.procesandoPaso = false;
        this.mensajeProceso = err?.error?.error ?? 'No se pudo solicitar sinodales.';
      },
    });
  }

  confirmarSinodalesRecibidos(): void {
    if (!this.detalleSeleccionado || this.procesandoPaso) return;
    this.procesandoPaso = true;
    this.mensajeProceso = '';
    this.egresadoService
      .confirmarSinodalesRecibidos(this.detalleSeleccionado.id)
      .pipe(
        timeout(25000),
        catchError((err) => {
          if (err?.name === 'TimeoutError') {
            return throwError(() => ({ error: { error: 'El servidor no respondió al confirmar sinodales. Intenta de nuevo.' } }));
          }
          return throwError(() => err);
        }),
      )
      .subscribe({
        next: () => {
          this.procesandoPaso = false;
          this.mensajeProceso = 'Sinodales recibidos confirmados.';
          if (this.detalleSeleccionado) {
            this.detalleSeleccionado = {
              ...this.detalleSeleccionado,
              fecha_confirmacion_sinodales_recibidos: new Date().toISOString(),
            };
            this.actualizarPasosProcesoTitulacion();
          }
          this.refrescarDetalle();
        },
        error: (err) => {
          this.procesandoPaso = false;
          this.mensajeProceso = err?.error?.error ?? 'No se pudo confirmar sinodales.';
        },
      });
  }

  onActoAgendado(dto: ActoAgendadoDto): void {
    if (!this.detalleSeleccionado || this.procesandoPaso) return;
    this.mostrarModalActo93 = false;
    const fechaHora = `${dto.fecha}T${dto.horaInicio}`;
    this.procesandoPaso = true;
    this.mensajeProceso = '';
    this.egresadoService
      .agendarActo93(this.detalleSeleccionado.id, fechaHora)
      .pipe(
        timeout(45000),
        catchError((err) => {
          if (err?.name === 'TimeoutError') {
            // El servidor sí procesa el guardado pero el envío de correos a sinodales puede tardar.
            // Refrescamos desde BD para mostrar la fecha real en lugar de mostrar un error falso.
            this.procesandoPaso = false;
            this.mensajeProceso = 'Acto agendado. Cargando datos actualizados...';
            this.refrescarDetalle();
            return EMPTY;
          }
          return throwError(() => err);
        }),
      )
      .subscribe({
        next: () => {
          this.procesandoPaso = false;
          this.mensajeProceso = 'Acto 9.3 agendado.';
          const d = new Date(`${dto.fecha}T${dto.horaInicio}`);
          const iso = isNaN(d.getTime()) ? fechaHora : d.toISOString();
          if (this.detalleSeleccionado) {
            this.detalleSeleccionado = { ...this.detalleSeleccionado, fecha_agenda_acto_9_3: iso };
            this.actualizarPasosProcesoTitulacion();
          }
          this.refrescarDetalle();
        },
        error: (err) => {
          this.procesandoPaso = false;
          this.mensajeProceso = err?.error?.error ?? 'No se pudo agendar 9.3.';
        },
      });
  }

  crearDescargar93(): void {
    if (!this.detalleSeleccionado || this.procesandoPaso) return;
    this.procesandoPaso = true;
    this.mensajeProceso = '';
    const nc = this.detalleSeleccionado.numero_control;
    this.egresadoService.descargarAnexo93(this.detalleSeleccionado.id).subscribe({
      next: (response) => {
        this.procesandoPaso = false;
        this.descargarBlob(response.body!, `Anexo-9.3-${nc}.pdf`);
        const notificados = Number(response.headers.get('X-Sinodales-Notificados') ?? '0');
        this.mensajeProceso = notificados > 0
          ? `Anexo 9.3 generado. Correo enviado a ${notificados} sinodal(es).`
          : 'Anexo 9.3 generado.';
        this.refrescarDetalle();
      },
      error: (err) => {
        this.procesandoPaso = false;
        void mensajeErrorApiConBlob(err, 'No se pudo generar 9.3.').then((m) => {
          this.mensajeProceso = m;
        });
      },
    });
  }

  confirmarEntrega93(): void {
    if (!this.detalleSeleccionado || this.procesandoPaso) return;
    if (!this.detalleSeleccionado.fecha_creacion_anexo_9_3) {
      this.mensajeProceso = 'Primero debe generarse el anexo 9.3.';
      return;
    }
    this.procesandoPaso = true;
    this.mensajeProceso = '';
    this.egresadoService.confirmarEntregaAnexo93(this.detalleSeleccionado.id).subscribe({
      next: () => {
        this.procesandoPaso = false;
        this.mensajeProceso = 'Entrega del anexo 9.3 confirmada.';
        this.refrescarDetalle();
      },
      error: (err: { error?: { error?: string } }) => {
        this.procesandoPaso = false;
        this.mensajeProceso = err?.error?.error ?? 'No se pudo confirmar la entrega del 9.3.';
      },
    });
  }

  solicitarDocumentacionEscaneada(): void {
    if (!this.detalleSeleccionado || this.procesandoPaso) return;
    this.procesandoPaso = true;
    this.mensajeProceso = '';
    this.egresadoService.solicitarDocumentacionEscaneada(this.detalleSeleccionado.id).subscribe({
      next: () => {
        this.procesandoPaso = false;
        this.mensajeProceso = 'Solicitud de documentación escaneada registrada.';
        this.refrescarDetalle();
      },
      error: (err) => {
        this.procesandoPaso = false;
        this.mensajeProceso = err?.error?.error ?? 'No se pudo registrar la solicitud.';
      },
    });
  }

  confirmarDocumentacionEscaneadaRecibida(): void {
    if (!this.detalleSeleccionado || this.procesandoPaso) return;
    this.procesandoPaso = true;
    this.mensajeProceso = '';
    this.egresadoService.confirmarDocumentacionEscaneadaRecibida(this.detalleSeleccionado.id).subscribe({
      next: () => {
        this.procesandoPaso = false;
        this.mensajeProceso = 'Recepción de documentación escaneada confirmada.';
        this.refrescarDetalle();
      },
      error: (err) => {
        this.procesandoPaso = false;
        this.mensajeProceso = err?.error?.error ?? 'No se pudo confirmar la recepción.';
      },
    });
  }

  solicitarDocumentacionEscaneadaNuevamente(): void {
    if (!this.detalleSeleccionado || this.procesandoPaso) return;
    const obs = this.observacionesReenvioDocEscaneada.trim();
    if (!obs) {
      this.mensajeProceso = 'Escribe observaciones para solicitar corrección.';
      return;
    }
    this.procesandoPaso = true;
    this.mensajeProceso = '';
    this.egresadoService
      .solicitarDocumentacionEscaneadaNuevamente(this.detalleSeleccionado.id, obs)
      .subscribe({
        next: () => {
          this.procesandoPaso = false;
          this.mensajeProceso = 'Se solicitó nuevamente la documentación escaneada al egresado.';
          this.observacionesReenvioDocEscaneada = '';
          this.refrescarDetalle();
        },
        error: (err) => {
          this.procesandoPaso = false;
          this.mensajeProceso = err?.error?.error ?? 'No se pudo solicitar nuevamente.';
        },
      });
  }

  private cargar(silencioso = false): void {
    if (!silencioso) {
      this.cargando = true;
      this.error = '';
    }
    this.egresadoService.listar({ aplicar_scope_departamento: false }).subscribe({
      next: (lista: EgresadoItem[]) => {
        this.items = lista.map((e) => this.mapearItem(e));
        if (!silencioso) this.cargando = false;
      },
      error: () => {
        if (!silencioso) {
          this.error = 'No se pudo cargar el seguimiento.';
          this.cargando = false;
        }
      },
    });
  }

  private isoUltimoMovimiento(e: EgresadoItem): string {
    return (
      e.fecha_actualizacion ||
      e.fecha_envio_documentacion_escaneada_egresado ||
      e.fecha_confirmacion_documentacion_escaneada_recibida ||
      e.fecha_creacion ||
      ''
    );
  }

  private mapearItem(e: EgresadoItem): SeguimientoItem {
    const hoy = new Date();
    const modalidad = e.modalidad?.trim() || '—';

    const isoUltimo = this.isoUltimoMovimiento(e);
    const ultimoMovimiento = isoUltimo ? this.formatoFecha(new Date(isoUltimo)) : '—';

    const esRes = this.catalogoService.esResidencia(modalidad.trim());
    const esCeneval = this.catalogoService.esCeneval(modalidad.trim());
    if (e.fecha_confirmacion_documentacion_escaneada_recibida) {
      return {
        id: e.id,
        alumno: e.nombre || '—',
        noControl: e.numero_control || '—',
        modalidad,
        carrera: e.carrera || '—',
        estado: 'concluido',
        documentoFaltante: 'Proceso concluido',
        ultimoMovimiento,
        ultimoMovimientoIso: isoUltimo,
        fechaLimite: 'Finalizado',
      };
    }

    if (esCeneval) {
      return {
        id: e.id,
        alumno: e.nombre || '—',
        noControl: e.numero_control || '—',
        modalidad,
        carrera: e.carrera || '—',
        estado: 'en_tiempo',
        documentoFaltante: 'En curso',
        ultimoMovimiento,
        ultimoMovimientoIso: isoUltimo,
        fechaLimite: '—',
      };
    }

    if (!esRes) {
      const plazos = calcularVistaPlazosNoResidencia(
        {
          modalidad,
          fecha_creacion: e.fecha_creacion,
          fecha_envio_solicitud_registro_anteproyecto_depto_academico:
            e.fecha_envio_solicitud_registro_anteproyecto_depto_academico,
          fecha_confirmacion_recepcion_inicial_anexos_xxxi_xxxii:
            e.fecha_confirmacion_recepcion_inicial_anexos_xxxi_xxxii,
          fecha_solicitud_registro_liberacion_depto_academico:
            e.fecha_solicitud_registro_liberacion_depto_academico,
          fecha_enviado_departamento_academico: e.fecha_enviado_departamento_academico,
          fecha_confirmacion_recibidos_anexo_xxxi_xxxii: e.fecha_confirmacion_recibidos_anexo_xxxi_xxxii,
          fecha_confirmacion_documentacion_escaneada_recibida: e.fecha_confirmacion_documentacion_escaneada_recibida,
        },
        hoy,
      );
      const estado = plazos.estadoGlobal;
      const fechaLimite = plazos.fechaLimiteMasCercana
        ? this.formatoFecha(plazos.fechaLimiteMasCercana)
        : '—';
      const documentoFaltante =
        estado === 'vencido' ? 'Plazo vencido' : estado === 'rezagado' ? 'En curso (cerca del límite)' : 'En curso';
      return {
        id: e.id,
        alumno: e.nombre || '—',
        noControl: e.numero_control || '—',
        modalidad,
        carrera: e.carrera || '—',
        estado,
        documentoFaltante,
        ultimoMovimiento,
        ultimoMovimientoIso: isoUltimo,
        fechaLimite,
      };
    }

    const plazos = calcularVistaPlazosResidencia(
      {
        fecha_registro_anexo_xxxi: e.fecha_registro_anexo_xxxi,
        fecha_creacion: e.fecha_creacion,
        fecha_confirmacion_documentacion_escaneada_recibida: e.fecha_confirmacion_documentacion_escaneada_recibida,
      },
      hoy,
    );
    const estado = plazos.estadoGlobal;
    const fechaLimite = plazos.fechaLimiteMasCercana
      ? this.formatoFecha(plazos.fechaLimiteMasCercana)
      : '—';
    const documentoFaltante =
      estado === 'vencido' ? 'Plazo vencido' : estado === 'rezagado' ? 'En curso (cerca del límite)' : 'En curso';
    return {
      id: e.id,
      alumno: e.nombre || '—',
      noControl: e.numero_control || '—',
      modalidad,
      carrera: e.carrera || '—',
      estado,
      documentoFaltante,
      ultimoMovimiento,
      ultimoMovimientoIso: isoUltimo,
      fechaLimite,
    };
  }

  private formatoFecha(d: Date): string {
    const dd = String(d.getDate()).padStart(2, '0');
    const mm = String(d.getMonth() + 1).padStart(2, '0');
    const yyyy = d.getFullYear();
    return `${dd}/${mm}/${yyyy}`;
  }

}

