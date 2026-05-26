import { Component, HostListener, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HeaderComponent } from '../../layout/header/header.component';
import { EgresadoService, EgresadoDetail, RevisionApi } from '../../services/egresado.service';
import { CatalogoService } from '../../services/catalogo.service';
import {
  calcularVistaPlazosNoResidencia,
  calcularVistaPlazosResidencia,
  MESES_PLAZO_TITULACION_NO_RES,
} from '../../core/plazos-titulacion-no-residencia';
import {
  calcularVistaPlazoDesarrolloRecepcionNoRes,
  construirPlazoDesarrolloRecepcionUi,
  mesesPlazoDesarrolloProyectoNoRes,
  type PlazoDesarrolloRecepcionUi,
} from '../../core/plazo-desarrollo-proyecto-no-res';

/** Paso mostrado al alumno (solo lectura). */
export interface PasoAlumnoVista {
  numero: number;
  titulo: string;
  detalle: string;
  /** Si existe, se muestra en negrita después del detalle (p. ej. fecha/hora del acto). */
  fechaDetalleResaltada?: string;
  fecha: string;
  completado: boolean;
  activo: boolean;
  /** Identifica bloques especiales en la plantilla (revisiones, subida de PDF, etc.). */
  clave?: string;
  /** Cuadro de semáforo del plazo de desarrollo (recepción en división, flujo 16). */
  plazoRecepcion?: PlazoDesarrolloRecepcionUi | null;
}

type EstadoAvance = 'en_tiempo' | 'rezagado' | 'vencido';

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

@Component({
  selector: 'app-seguimiento',
  standalone: true,
  imports: [CommonModule, HeaderComponent],
  templateUrl: './seguimiento.component.html',
  styleUrl: './seguimiento.component.css',
})
export class SeguimientoComponent implements OnInit {
  datos: EgresadoDetail | null = null;
  cargando = true;
  error = '';
  cargandoRevisionesEnviadas = false;
  revisionesEnviadas: RevisionApi[] = [];
  mostrarPanelRevisiones = false;
  mostrarHistorial = false;
  /** 'actual' = proceso activo, number = índice en procesos_anteriores */
  tabActiva: 'actual' | number = 'actual';

  /** Datos del proceso que se está visualizando (activo o anterior). */
  get datosVista(): EgresadoDetail | null {
    if (this.tabActiva === 'actual' || !this.datos) return this.datos;
    const p = this.datos.procesos_anteriores?.[this.tabActiva as number];
    if (!p) return this.datos;
    return {
      ...this.datos,
      estado_general: p.estado,
      fecha_creacion: p.fecha_creacion,
      fecha_actualizacion: p.fecha_cierre ?? p.fecha_creacion,
      datos_proyecto: {
        nombre_proyecto: p.nombre_proyecto,
        modalidad: p.modalidad,
        curso_titulacion: 'no',
      },
      fecha_enviado_departamento_academico: p.fecha_enviado_departamento_academico,
      fecha_recibido_registro_liberacion: p.fecha_recibido_registro_liberacion,
      fecha_confirmacion_recibidos_anexo_xxxi_xxxii: p.fecha_confirmacion_recibidos_anexo_xxxi_xxxii,
      fecha_liberacion_documento_coordinacion_cat: p.fecha_liberacion_documento_coordinacion_cat,
      fecha_confirmacion_entrega_egresado_depto: p.fecha_confirmacion_entrega_egresado_depto,
      fecha_envio_solicitud_registro_anteproyecto_depto_academico: p.fecha_envio_solicitud_registro_anteproyecto_depto_academico,
      fecha_confirmacion_recepcion_inicial_anexos_xxxi_xxxii: p.fecha_confirmacion_recepcion_inicial_anexos_xxxi_xxxii,
      fecha_recepcion_trabajo_division_estudios_prof: p.fecha_recepcion_trabajo_division_estudios_prof,
      fecha_solicitud_registro_liberacion_depto_academico: p.fecha_solicitud_registro_liberacion_depto_academico,
      fecha_recepcion_registro_liberacion_depto_academico: p.fecha_recepcion_registro_liberacion_depto_academico,
      fecha_creacion_anexo_9_1: p.fecha_creacion_anexo_9_1,
      fecha_confirmacion_entrega_anexo_9_1: p.fecha_confirmacion_entrega_anexo_9_1,
      fecha_solicitud_anexo_9_2: p.fecha_solicitud_anexo_9_2,
      fecha_confirmacion_recibido_anexo_9_2: p.fecha_confirmacion_recibido_anexo_9_2,
      fecha_solicitud_sinodales: p.fecha_solicitud_sinodales,
      fecha_confirmacion_sinodales_recibidos: p.fecha_confirmacion_sinodales_recibidos,
      fecha_agenda_acto_9_3: p.fecha_agenda_acto_9_3,
      fecha_reagenda_acto_9_3: undefined,
      fecha_creacion_anexo_9_3: p.fecha_creacion_anexo_9_3,
      fecha_confirmacion_entrega_anexo_9_3: p.fecha_confirmacion_entrega_anexo_9_3,
      fecha_solicitud_documentacion_escaneada: p.fecha_solicitud_documentacion_escaneada,
      fecha_envio_documentacion_escaneada_egresado: p.fecha_envio_documentacion_escaneada_egresado,
      fecha_confirmacion_documentacion_escaneada_recibida: p.fecha_confirmacion_documentacion_escaneada_recibida,
      fecha_titulacion: p.fecha_titulacion,
      procesos_anteriores: [],
    } as EgresadoDetail;
  }

  get estaViendoProcesoAnterior(): boolean {
    return this.tabActiva !== 'actual';
  }
  errorDescargaRevision = '';
  private revisionesExpandidas = new Set<string>();
  archivosPdfEscaneados: File[] = [];
  enviandoDocEscaneada = false;
  mensajeDocEscaneada = '';
  archivoCorreccionExpediente: File | null = null;
  enviandoCorreccionExpediente = false;
  mensajeCorreccionExpediente = '';

  get revisionesParaCorregir(): RevisionApi[] {
    return this.revisionesEnviadas.filter((r) => r.resultado === 'observaciones');
  }

  /** Documento liberado / aprobado por Coordinación de Apoyo a la Titulación (o residencia). */
  get expedienteAprobadoPorCoordinacion(): boolean {
    const d = this.datosVista;
    if (!d) return false;
    return !!(
      d.fecha_liberacion_documento_coordinacion_cat?.trim() || d.fecha_recibido_registro_liberacion?.trim()
    );
  }

  get totalRevisionesPendientes(): number {
    if (this.expedienteAprobadoPorCoordinacion) return 0;
    return this.revisionesParaCorregir.length;
  }

  get tieneRevisionesPendientes(): boolean {
    return this.totalRevisionesPendientes > 0;
  }

  get etapaRevisionesCompletada(): boolean {
    // Si ya existe anexo 9.1, el flujo de revisiones iniciales ya quedó atrás.
    return !!this.datos?.fecha_creacion_anexo_9_1;
  }

  get resumenRevisionesPaso(): string {
    if (this.etapaRevisionesCompletada) return 'Revisiones completadas.';
    if (this.expedienteAprobadoPorCoordinacion) {
      return 'Tu expediente fue aprobado por Coordinación de Apoyo a la Titulación.';
    }
    const total = this.totalRevisionesPendientes;
    if (total <= 0) return 'Sin revisiones pendientes.';
    if (total === 1) return '1 revisión pendiente de atender.';
    return `${total} revisiones pendientes de atender.`;
  }

  get esResidenciaProfesional(): boolean {
    const m = this.datosVista?.datos_proyecto?.modalidad?.trim() ?? '';
    return this.catalogoService.esResidencia(m);
  }

  get esCenevalProfesional(): boolean {
    const m = this.datosVista?.datos_proyecto?.modalidad?.trim() ?? '';
    return this.catalogoService.esCeneval(m);
  }

  get noResidenciaFlujoLegacy(): boolean {
    const d = this.datosVista;
    if (!d || this.esResidenciaProfesional || this.esCenevalProfesional) return false;
    return !!d.fecha_enviado_departamento_academico && !d.fecha_envio_solicitud_registro_anteproyecto_depto_academico;
  }

  get procesoBloqueadoPorVencimientoPlazoAlumno(): boolean {
    if (this.estaViendoProcesoAnterior) return false;
    const d = this.datosVista;
    if (!d) return false;
    if (d.estado_general === 'titulado') return false;
    if (d.estado_general === 'vencido') return true;
    if (this.esResidenciaProfesional || this.noResidenciaFlujoLegacy || this.esCenevalProfesional) return false;
    const raw = calcularVistaPlazoDesarrolloRecepcionNoRes(d);
    return raw?.estado === 'vencido';
  }

  private pasoActivoPorClave(clave: string): boolean {
    const p = this.pasosAlumno.find((x) => x.clave === clave);
    return !!p && p.activo && !p.completado;
  }

  /** Formulario de PDF: paso unificado de documentación escaneada (solicitud + envío). */
  get mostrarFormularioSubidaDocEscaneada(): boolean {
    const d = this.datos;
    if (!d?.fecha_solicitud_documentacion_escaneada) return false;
    if (d.fecha_envio_documentacion_escaneada_egresado) return false;
    if (d.fecha_confirmacion_documentacion_escaneada_recibida) return false;
    return this.pasoActivoPorClave('doc_escaneada_subir') || this.pasoActivoPorClave('doc_escaneada_unificada');
  }

  /** Mensaje “enviado, esperando DEP” dentro del mismo paso unificado. */
  get mostrarEstadoDocEscaneadaEnviada(): boolean {
    const d = this.datos;
    if (!d?.fecha_envio_documentacion_escaneada_egresado || d.fecha_confirmacion_documentacion_escaneada_recibida) {
      return false;
    }
    return this.pasoActivoPorClave('doc_escaneada_espera') || this.pasoActivoPorClave('doc_escaneada_unificada');
  }

  /**
   * Pasos del flujo alineados con coordinación: residencia, no residencia legacy (14 pasos) o no residencia (7 pasos + revisión + anexos).
   */
  get pasosAlumno(): PasoAlumnoVista[] {
    const d = this.datosVista;
    if (!d) return [];
    if (this.esResidenciaProfesional) return this.construirPasosAlumnoResidencia();
    if (this.esCenevalProfesional) return this.construirPasosAlumnoCeneval();
    if (this.noResidenciaFlujoLegacy) return this.construirPasosAlumnoLegacyNoRes();
    return this.construirPasosAlumnoNoRes16();
  }

  private aplicarActivoPasos(raw: Omit<PasoAlumnoVista, 'activo'>[]): PasoAlumnoVista[] {
    const idxActivo = raw.findIndex((p) => !p.completado);
    return raw.map((p, i) => ({
      ...p,
      activo: idxActivo >= 0 && i === idxActivo,
    }));
  }

  /** Texto del paso «acto agendado»; si hubo reagenda, indica que los pasos posteriores se deben repetir. */
  private detalleTextoPasoActo93AgendadoAlumno(d: EgresadoDetail): string {
    if (d.fecha_reagenda_acto_9_3) {
      return 'Se reagendó tu acto protocolario. Debes completar de nuevo los pasos que siguen hasta concluir. Nueva fecha y horario:';
    }
    if (d.fecha_agenda_acto_9_3) {
      return 'La DEP agendó fecha y horario para la realización de tu acto protocolario. Tu fecha y horario es:';
    }
    return 'Cuando se registre el agendamiento, aquí verás la fecha y horario de tu acto protocolario.';
  }

  private construirPasosAlumnoResidencia(): PasoAlumnoVista[] {
    const d = this.datosVista!;
    const fh = (iso?: string | null): string => (iso ? this.formatearFechaHora(iso) : '—');
    const modalidad = (d.datos_proyecto?.modalidad ?? '').trim() || 'titulación integral';
    const c1 = !!d.fecha_creacion;
    const c2 = !!d.fecha_enviado_departamento_academico;
    const c3 = !!d.fecha_confirmacion_recibidos_anexo_xxxi_xxxii;
    const c4 = !!d.fecha_creacion_anexo_9_1;
    const c5 = !!d.fecha_confirmacion_entrega_anexo_9_1;
    const c6 = !!d.fecha_solicitud_anexo_9_2;
    const c7 = !!d.fecha_confirmacion_recibido_anexo_9_2;
    const c8 = !!d.fecha_solicitud_sinodales;
    const c9 = !!d.fecha_confirmacion_sinodales_recibidos;
    const c10 = !!d.fecha_agenda_acto_9_3;
    const c11 = !!d.fecha_creacion_anexo_9_3;
    const c12s = !!d.fecha_solicitud_documentacion_escaneada;
    const c12e = !!d.fecha_envio_documentacion_escaneada_egresado;
    const c12r = !!d.fecha_confirmacion_documentacion_escaneada_recibida;
    const raw: Omit<PasoAlumnoVista, 'activo'>[] = [
      {
        numero: 1,
        titulo: 'Registro de tu solicitud',
        detalle: `Se registró tu solicitud para iniciar el trámite del proceso de titulación por la opción: ${modalidad}.`,
        fecha: fh(d.fecha_creacion),
        completado: c1,
      },
      {
        numero: 2,
        titulo: 'Envío de solicitud al departamento académico .',
        detalle:
          'La DEP envió la solicitud para registro y liberación de tu proyecto de titulación integral por residencia al departamento académico.',
        fecha: fh(d.fecha_enviado_departamento_academico),
        completado: c2,
      },
      {
        numero: 3,
        titulo: 'Recepción de anexos XXXII y XXXIII',
        detalle:
          'La DEP recibió los anexos XXXII y XXXIII (registro y liberación) de tu proyecto de titulación integral por parte del departamento académico.',
        fecha: fh(d.fecha_confirmacion_recibidos_anexo_xxxi_xxxii),
        completado: c3,
      },
      {
        numero: 4,
        titulo: 'Recoger y firmar anexo 9.1',
        detalle:
          'Acude a  la división de estudios profesionales para recoger y firmar tu anexo 9.1 (formato de solicitud de acto de recepción profesional).',
        fecha: fh(d.fecha_creacion_anexo_9_1),
        completado: c4,
      },
      {
        numero: 5,
        titulo: 'Confirmación de anexo 9.1 firmado',
        detalle:
          'La DEP te entrego el  anexo 9.1 (formato de solicitud de acto de recepción profesional) .',
        fecha: fh(d.fecha_confirmacion_entrega_anexo_9_1),
        completado: c5,
      },
      {
        numero: 6,
        titulo: 'Solicitud del anexo 9.2',
        detalle:
          'Solicita en el Departamento de Servicios Escolares el  anexo 9.2 (constancia de no inconveniencia para acto de recepción profesional) y posteriormente entregarlo en la dep para continuar  con tu tramite .',
        fecha: fh(d.fecha_solicitud_anexo_9_2),
        completado: c6,
      },
      {
        numero: 7,
        titulo: 'Constancia 9.2 recibida',
        detalle:
          'Quedó registrada la recepción de la constancia 9.2 (constancia de no inconveniencia para acto de recepción profesional) en división de estudios profesionales.',
        fecha: fh(d.fecha_confirmacion_recibido_anexo_9_2),
        completado: c7,
      },
      {
        numero: 8,
        titulo: 'Solicitud de sinodales',
        detalle: 'La DEP solicitó al departamento académico la asignación de sinodales.',
        fecha: fh(d.fecha_solicitud_sinodales),
        completado: c8,
      },
      {
        numero: 9,
        titulo: 'Oficio de sinodales recibido',
        detalle: 'Quedó confirmada la recepción del oficio de sinodales que el departamento académico entregó a la DEP.',
        fecha: fh(d.fecha_confirmacion_sinodales_recibidos),
        completado: c9,
      },
      {
        numero: 10,
        titulo: 'Acto protocolario agendado',
        detalle: this.detalleTextoPasoActo93AgendadoAlumno(d),
        fechaDetalleResaltada: d.fecha_agenda_acto_9_3 ? fh(d.fecha_agenda_acto_9_3) : undefined,
        fecha: fh(d.fecha_agenda_acto_9_3),
        completado: c10,
      },
      {
        numero: 11,
        titulo: 'Anexo 9.3 generado',
        detalle:
          'La DEP generó el anexo 9.3 (aviso de realización de acto protocolario de titulación integral). Favor  de pasar a Division de Estudios Profesionales  para recoger y firmar.  .',
        fecha: fh(d.fecha_creacion_anexo_9_3),
        completado: c11,
      },
      {
        numero: 12,
        clave: 'doc_escaneada_subir',
        titulo: 'La división de estudios profesionales solicita que subas al SITVO la documentación escaneada.',
        detalle: c12e
          ? 'Quedó registrado el envío de tu archivo PDF.'
          : c12s
            ? d.observaciones_reenvio_documentacion_escaneada?.trim()
              ? `La DEP solicitó corrección. Observaciones: ${d.observaciones_reenvio_documentacion_escaneada}`
              : ''
            : 'Cuando la división de estudios solicite la documentación, aquí podrás subirla.',
        fecha: fh(
          c12e
            ? d.fecha_envio_documentacion_escaneada_egresado
            : c12s
              ? d.fecha_solicitud_documentacion_escaneada
              : undefined,
        ),
        completado: c12e,
      },
      {
        numero: 13,
        clave: 'doc_escaneada_espera',
        titulo: 'Tu proceso por esta opción quedó concluida.',
        detalle: c12r
          ? 'La DEP confirmó la recepción de documentación escaneada.'
          : c12e
            ? 'La DEP confirmará la recepción de documentación escaneada en cuanto revise tu archivo.'
            : d.observaciones_reenvio_documentacion_escaneada?.trim()
              ? `Pendiente de nuevo envío del egresado. Observaciones: ${d.observaciones_reenvio_documentacion_escaneada}`
              : 'Cuando envíes tu archivo PDF, este paso se activará para la confirmación de la DEP.',
        fecha: fh(
          c12r
            ? d.fecha_confirmacion_documentacion_escaneada_recibida
            : c12e
              ? d.fecha_envio_documentacion_escaneada_egresado
              : undefined,
        ),
        completado: c12r,
      },
    ];
    return this.aplicarActivoPasos(raw);
  }

  private construirPasosAlumnoCeneval(): PasoAlumnoVista[] {
    const d = this.datosVista!;
    const fh = (iso?: string | null): string => (iso ? this.formatearFechaHora(iso) : '—');
    const modalidad = (d.datos_proyecto?.modalidad ?? '').trim() || 'CENEVAL';
    const c1 = !!d.fecha_creacion;
    const c2 = !!d.fecha_confirmacion_entrega_egresado_depto;
    const c3 = !!d.fecha_creacion_anexo_9_1;
    const c4 = !!d.fecha_confirmacion_entrega_anexo_9_1;
    const c5 = !!d.fecha_solicitud_anexo_9_2;
    const c6 = !!d.fecha_confirmacion_recibido_anexo_9_2;
    const c7 = !!d.fecha_solicitud_sinodales;
    const c8 = !!d.fecha_confirmacion_sinodales_recibidos;
    const c9 = !!d.fecha_agenda_acto_9_3;
    const c10 = !!d.fecha_creacion_anexo_9_3;
    const c10b = !!d.fecha_confirmacion_entrega_anexo_9_3;
    const c11s = !!d.fecha_solicitud_documentacion_escaneada;
    const c11e = !!d.fecha_envio_documentacion_escaneada_egresado;
    const c11r = !!d.fecha_confirmacion_documentacion_escaneada_recibida;
    const raw: Omit<PasoAlumnoVista, 'activo'>[] = [
      {
        numero: 1,
        titulo: 'Registro de tu solicitud',
        detalle: `Se registró tu solicitud para iniciar el trámite del proceso de titulación por la opción: ${modalidad}.`,
        fecha: fh(d.fecha_creacion),
        completado: c1,
      },
      {
        numero: 2,
        titulo: 'Recibimos solicitud y copia del testimonio por la opción de examen CENEVAL',
        detalle:
          'La división de estudios profesionales confirmó la recepción de tu solicitud y la copia del testimonio para el trámite por examen CENEVAL.',
        fecha: fh(d.fecha_confirmacion_entrega_egresado_depto),
        completado: c2,
      },
      {
        numero: 3,
        titulo: 'Recoger y firmar anexo 9.1',
        detalle:
          'Acude a la división de estudios profesionales para recoger y firmar tu anexo 9.1 (formato de solicitud de acto de recepción profesional).',
        fecha: fh(d.fecha_creacion_anexo_9_1),
        completado: c3,
      },
      {
        numero: 4,
        titulo: 'Confirmación de anexo 9.1 firmado',
        detalle:
          'La DEP te entregó el anexo 9.1 (formato de solicitud de acto de recepción profesional).',
        fecha: fh(d.fecha_confirmacion_entrega_anexo_9_1),
        completado: c4,
      },
      {
        numero: 5,
        titulo: 'Solicitud del anexo 9.2',
        detalle:
          'Solicita en el Departamento de Servicios Escolares el anexo 9.2 (constancia de no inconveniencia para acto de recepción profesional) y posteriormente entrégalo en la DEP para continuar con tu trámite.',
        fecha: fh(d.fecha_solicitud_anexo_9_2),
        completado: c5,
      },
      {
        numero: 6,
        titulo: 'Constancia 9.2 recibida',
        detalle:
          'Quedó registrada la recepción de la constancia 9.2 (constancia de no inconveniencia para acto de recepción profesional) en división de estudios profesionales.',
        fecha: fh(d.fecha_confirmacion_recibido_anexo_9_2),
        completado: c6,
      },
      {
        numero: 7,
        titulo: 'Solicitud de sinodales',
        detalle: 'La DEP solicitó al departamento académico la asignación de sinodales.',
        fecha: fh(d.fecha_solicitud_sinodales),
        completado: c7,
      },
      {
        numero: 8,
        titulo: 'Oficio de sinodales recibido',
        detalle: 'Quedó confirmada la recepción del oficio de sinodales que el departamento académico entregó a la DEP.',
        fecha: fh(d.fecha_confirmacion_sinodales_recibidos),
        completado: c8,
      },
      {
        numero: 9,
        titulo: 'Acto protocolario agendado',
        detalle: this.detalleTextoPasoActo93AgendadoAlumno(d),
        fechaDetalleResaltada: d.fecha_agenda_acto_9_3 ? fh(d.fecha_agenda_acto_9_3) : undefined,
        fecha: fh(d.fecha_agenda_acto_9_3),
        completado: c9,
      },
      {
        numero: 10,
        titulo: 'Anexo 9.3 generado',
        detalle:
          'La DEP generó el anexo 9.3 (aviso de realización de acto protocolario de titulación integral). Favor de pasar a División de Estudios Profesionales para recoger y firmar.',
        fecha: fh(d.fecha_creacion_anexo_9_3),
        completado: c10,
      },
      {
        numero: 11,
        titulo: 'Recoger y firmar anexo 9.3',
        detalle: c10b
          ? 'Quedó registrada la entrega de tu anexo 9.3 en división de estudios profesionales.'
          : 'Acude a división de estudios profesionales para recoger y firmar tu anexo 9.3 antes de continuar con la documentación escaneada.',
        fecha: fh(d.fecha_confirmacion_entrega_anexo_9_3),
        completado: c10b,
      },
      {
        numero: 12,
        clave: 'doc_escaneada_subir',
        titulo: 'La división de estudios profesionales solicita que subas al SITVO la documentación escaneada.',
        detalle: c11e
          ? 'Quedó registrado el envío de tu archivo PDF.'
          : c11s
            ? d.observaciones_reenvio_documentacion_escaneada?.trim()
              ? `La DEP solicitó corrección. Observaciones: ${d.observaciones_reenvio_documentacion_escaneada}`
              : ''
            : 'Cuando la división de estudios solicite la documentación, aquí podrás subirla.',
        fecha: fh(
          c11e
            ? d.fecha_envio_documentacion_escaneada_egresado
            : c11s
              ? d.fecha_solicitud_documentacion_escaneada
              : undefined,
        ),
        completado: c11e,
      },
      {
        numero: 13,
        clave: 'doc_escaneada_espera',
        titulo: 'Tu proceso por esta opción quedó concluida.',
        detalle: c11r
          ? 'La DEP confirmó la recepción de documentación escaneada.'
          : c11e
            ? 'La DEP confirmará la recepción de documentación escaneada en cuanto revise tu archivo.'
            : d.observaciones_reenvio_documentacion_escaneada?.trim()
              ? `Pendiente de nuevo envío del egresado. Observaciones: ${d.observaciones_reenvio_documentacion_escaneada}`
              : 'Cuando envíes tu archivo PDF, este paso se activará para la confirmación de la DEP.',
        fecha: fh(
          c11r
            ? d.fecha_confirmacion_documentacion_escaneada_recibida
            : c11e
              ? d.fecha_envio_documentacion_escaneada_egresado
              : undefined,
        ),
        completado: c11r,
      },
    ];
    return this.aplicarActivoPasos(raw);
  }

  private construirPasosAlumnoLegacyNoRes(): PasoAlumnoVista[] {
    const d = this.datosVista!;
    const fh = (iso?: string | null): string => (iso ? this.formatearFechaHora(iso) : '—');
    const modalidad = (d.datos_proyecto?.modalidad ?? '').trim() || 'titulación integral';
    const c1 = !!d.fecha_creacion;
    const c2 = !!d.fecha_enviado_departamento_academico;
    const c3 = !!d.fecha_confirmacion_recibidos_anexo_xxxi_xxxii;
    const c4 = !!d.fecha_creacion_anexo_9_1;
    const c5 = !!d.fecha_confirmacion_entrega_anexo_9_1;
    const c6 = !!d.fecha_solicitud_anexo_9_2;
    const c7 = !!d.fecha_confirmacion_recibido_anexo_9_2;
    const c8 = !!d.fecha_solicitud_sinodales;
    const c9 = !!d.fecha_confirmacion_sinodales_recibidos;
    const c10 = !!d.fecha_agenda_acto_9_3;
    const c11 = !!d.fecha_creacion_anexo_9_3;
    const c12s = !!d.fecha_solicitud_documentacion_escaneada;
    const c12e = !!d.fecha_envio_documentacion_escaneada_egresado;
    const c12r = !!d.fecha_confirmacion_documentacion_escaneada_recibida;
    const raw: Omit<PasoAlumnoVista, 'activo'>[] = [
      {
        numero: 1,
        titulo: 'Registro de tu solicitud',
        detalle: `Se registró tu solicitud para iniciar el trámite del proceso de titulación por la opción: ${modalidad}.`,
        fecha: fh(d.fecha_creacion),
        completado: c1,
      },
      {
        numero: 2,
        titulo: 'Envío de solicitud al Departamento de Apoyo a la Titulación',
        detalle:
          'La DEP envía la solicitud de registro, revisión y aprobación del proyecto de titulación integral al Departamento de Apoyo a la Titulación. Si cuentas con correcciones, aquí se mostrarán las revisiones realizadas por la Coordinación de Apoyo a la Titulación en cuanto sean enviadas.',
        fecha: fh(d.fecha_enviado_departamento_academico),
        completado: c2,
      },
      {
        numero: 3,
        clave: 'revisiones_apoyo',
        titulo: 'Recepción de anexos XXXII y XXXIII',
        detalle:
          'La DEP recibe los anexos XXXII y XXXIII (registro y aprobación) del proyecto de titulación integral por parte del Departamento de Apoyo a la Titulación.',
        fecha: fh(d.fecha_confirmacion_recibidos_anexo_xxxi_xxxii),
        completado: c3,
      },
      {
        numero: 4,
        titulo: 'Recoger y firmar anexo 9.1',
        detalle:
          'Acude a división de estudios profesionales para recoger y firmar tu anexo 9.1 (formato de solicitud de acto de recepción profesional).',
        fecha: fh(d.fecha_creacion_anexo_9_1),
        completado: c4,
      },
      {
        numero: 5,
        titulo: 'Confirmación de anexo 9.1 firmado',
        detalle:
          'La DEP confirmó la recepción de tu anexo 9.1 (formato de solicitud de acto de recepción profesional) firmado.',
        fecha: fh(d.fecha_confirmacion_entrega_anexo_9_1),
        completado: c5,
      },
      {
        numero: 6,
        titulo: 'Solicitud del anexo 9.2',
        detalle:
          'Solicita en servicios escolares tu anexo 9.2 (constancia de no inconveniencia para acto de recepción profesional) y entrégalo en la DEP para continuar con el trámite.',
        fecha: fh(d.fecha_solicitud_anexo_9_2),
        completado: c6,
      },
      {
        numero: 7,
        titulo: 'Constancia 9.2 recibida',
        detalle:
          'Quedó registrada la recepción de la constancia 9.2 (constancia de no inconveniencia para acto de recepción profesional) en división de estudios profesionales.',
        fecha: fh(d.fecha_confirmacion_recibido_anexo_9_2),
        completado: c7,
      },
      {
        numero: 8,
        titulo: 'Solicitud de sinodales',
        detalle: 'La DEP solicitó al departamento académico la asignación de sinodales.',
        fecha: fh(d.fecha_solicitud_sinodales),
        completado: c8,
      },
      {
        numero: 9,
        titulo: 'Oficio de sinodales recibido',
        detalle: 'Quedó confirmada la recepción del oficio de sinodales que el departamento académico entregó a la DEP.',
        fecha: fh(d.fecha_confirmacion_sinodales_recibidos),
        completado: c9,
      },
      {
        numero: 10,
        titulo: 'Acto protocolario agendado',
        detalle: this.detalleTextoPasoActo93AgendadoAlumno(d),
        fechaDetalleResaltada: d.fecha_agenda_acto_9_3 ? fh(d.fecha_agenda_acto_9_3) : undefined,
        fecha: fh(d.fecha_agenda_acto_9_3),
        completado: c10,
      },
      {
        numero: 11,
        titulo: 'Anexo 9.3 generado',
        detalle:
          'La DEP generó el anexo 9.3 (aviso de realización de acto protocolario de titulación integral). Favor de recogerlo en división de estudios profesionales.',
        fecha: fh(d.fecha_creacion_anexo_9_3),
        completado: c11,
      },
      {
        numero: 12,
        clave: 'doc_escaneada_subir',
        titulo: 'La división de estudios profesionales solicita que subas al SITVO la documentación escaneada.',
        detalle: c12e
          ? 'Quedó registrado el envío de tu archivo PDF.'
          : c12s
            ? d.observaciones_reenvio_documentacion_escaneada?.trim()
              ? `La DEP solicitó corrección. Observaciones: ${d.observaciones_reenvio_documentacion_escaneada}`
              : 'Sube al SITVO un solo archivo .PDF con toda la documentación escaneada (no fotos).'
            : 'Cuando la división de estudios solicite la documentación, aquí podrás subirla.',
        fecha: fh(
          c12e
            ? d.fecha_envio_documentacion_escaneada_egresado
            : c12s
              ? d.fecha_solicitud_documentacion_escaneada
              : undefined,
        ),
        completado: c12e,
      },
      {
        numero: 13,
        clave: 'doc_escaneada_espera',
        titulo: 'Tu proceso por esta opción quedó concluida.',
        detalle: c12r
          ? 'La DEP confirmó la recepción de documentación escaneada.'
          : c12e
            ? 'La DEP confirmará la recepción de documentación escaneada en cuanto revise tu archivo.'
            : d.observaciones_reenvio_documentacion_escaneada?.trim()
              ? `Pendiente de nuevo envío del egresado. Observaciones: ${d.observaciones_reenvio_documentacion_escaneada}`
              : 'Cuando envíes tu archivo PDF, este paso se activará para la confirmación de la DEP.',
        fecha: fh(
          c12r
            ? d.fecha_confirmacion_documentacion_escaneada_recibida
            : c12e
              ? d.fecha_envio_documentacion_escaneada_egresado
              : undefined,
        ),
        completado: c12r,
      },
    ];
    return this.aplicarActivoPasos(raw);
  }

  /** Retrocompat: expedientes que avanzaron antes del campo de entrega en DEP. */
  private entregaEgresadoDeptoCompleta(d: EgresadoDetail): boolean {
    return !!(
      d.fecha_confirmacion_entrega_egresado_depto?.trim() ||
      d.fecha_envio_solicitud_registro_anteproyecto_depto_academico?.trim()
    );
  }

  private construirPasosAlumnoNoRes16(): PasoAlumnoVista[] {
    const d = this.datosVista!;
    const fh = (iso?: string | null): string => (iso ? this.formatearFechaHora(iso) : '—');
    const modalidad = (d.datos_proyecto?.modalidad ?? '').trim() || 'titulación integral';
    const c1 = !!d.fecha_creacion;
    const c2 = this.entregaEgresadoDeptoCompleta(d);
    const c3 = !!d.fecha_envio_solicitud_registro_anteproyecto_depto_academico;
    const c4 = !!d.fecha_confirmacion_recepcion_inicial_anexos_xxxi_xxxii;
    const c5 = !!d.fecha_solicitud_registro_liberacion_depto_academico;
    const c6 = !!d.fecha_recepcion_registro_liberacion_depto_academico;
    const c7 = !!d.fecha_enviado_departamento_academico;
    const c8 = !!(d.fecha_liberacion_documento_coordinacion_cat || d.fecha_confirmacion_recibidos_anexo_xxxi_xxxii);
    const c9 = !!d.fecha_creacion_anexo_9_1;
    const c10 = !!d.fecha_confirmacion_entrega_anexo_9_1;
    const c11 = !!d.fecha_solicitud_anexo_9_2;
    const c12 = !!d.fecha_confirmacion_recibido_anexo_9_2;
    const c13 = !!d.fecha_solicitud_sinodales;
    const c14 = !!d.fecha_confirmacion_sinodales_recibidos;
    const c15 = !!d.fecha_agenda_acto_9_3;
    const c16 = !!d.fecha_creacion_anexo_9_3;
    const c17s = !!d.fecha_solicitud_documentacion_escaneada;
    const c17e = !!d.fecha_envio_documentacion_escaneada_egresado;
    const c17r = !!d.fecha_confirmacion_documentacion_escaneada_recibida;
    const plazoRecepcion = construirPlazoDesarrolloRecepcionUi(d) ?? undefined;
    const fechaPasoRevision = d.fecha_liberacion_documento_coordinacion_cat || d.fecha_confirmacion_recibidos_anexo_xxxi_xxxii;
    const fechaEntregaDepto =
      d.fecha_confirmacion_entrega_egresado_depto || d.fecha_envio_solicitud_registro_anteproyecto_depto_academico;
    const raw: Omit<PasoAlumnoVista, 'activo'>[] = [
      {
        numero: 1,
        titulo: 'Registro de tu solicitud',
        detalle: `Se registró tu solicitud para iniciar el trámite del proceso de titulación por la opción: ${modalidad}.`,
        fecha: fh(d.fecha_creacion),
        completado: c1,
      },
      {
        numero: 2,
        titulo: 'Entrega en la DEP: solicitud de inicio (anexo XXXI) y anteproyecto',
        detalle:
          'La división de estudios profesionales confirmó que recibió tu solicitud de inicio de proceso de titulación y tu anteproyecto.',
        fecha: fh(fechaEntregaDepto),
        completado: c2,
      },
      {
        numero: 3,
        titulo: 'Envío al departamento académico de tu carrera',
        detalle:
          'La DEP envió a tu departamento académico el anteproyecto y la solicitud de registro de la tesis (anexo XXXII).',
        fecha: fh(d.fecha_envio_solicitud_registro_anteproyecto_depto_academico),
        completado: c3,
      },
      {
        numero: 4,
        titulo: 'Registro de la tesis en el departamento académico',
        detalle:
          'Tu departamento académico registró la tesis y la DEP confirmó la recepción del anexo XXXII.',
        fecha: fh(d.fecha_confirmacion_recepcion_inicial_anexos_xxxi_xxxii),
        completado: c4,
      },
      {
        numero: 5,
        titulo: `Desarrollo de tu proyecto de ${modalidad}`,
        detalle: this.detallePasoDesarrolloAlumnoNoRes16(d, modalidad),
        fecha: fh(c5 ? d.fecha_solicitud_registro_liberacion_depto_academico : d.fecha_confirmacion_recepcion_inicial_anexos_xxxi_xxxii),
        completado: c5,
        plazoRecepcion: c4 && !c5 ? plazoRecepcion : undefined,
      },
      {
        numero: 6,
        titulo: 'Liberación de producto en tu departamento académico',
        detalle: c5
          ? 'Tu departamento académico liberó la tesis (anexo XXXIII) y la envió a la división de estudios profesionales.'
          : 'Tu departamento académico liberará la tesis (anexo XXXIII) cuando concluyas el desarrollo; no requiere acción tuya en el SITVO.',
        fecha: fh(d.fecha_solicitud_registro_liberacion_depto_academico),
        completado: c5,
      },
      {
        numero: 7,
        titulo: 'Recepción en la DEP de la liberación y la tesis',
        detalle:
          'La división de estudios profesionales confirmó la recepción del anexo XXXIII y de tu documento de tesis.',
        fecha: fh(d.fecha_recepcion_registro_liberacion_depto_academico),
        completado: c6,
      },
      {
        numero: 8,
        titulo: 'Envío a coordinación de Apoyo a la Titulación',
        detalle:
          'Tu tesis fue enviada a la coordinación de apoyo a la titulación para la revisión de acuerdo a las normas de presentación para trabajos profesionales.',
        fecha: fh(d.fecha_enviado_departamento_academico),
        completado: c7,
      },
      {
        numero: 9,
        clave: 'revisiones_apoyo',
        titulo: 'Revisión de tu proyecto y liberación del documento',
        detalle:
          'La Coordinación de Apoyo a la Titulación revisa tu expediente. Si hay observaciones, te las notificaremos aquí. Cuando el proyecto quede aprobado, el documento queda liberado para continuar.',
        fecha: fh(fechaPasoRevision),
        completado: c8,
      },
      {
        numero: 10,
        titulo: 'Recoger y firmar anexo 9.1',
        detalle:
          'Acude a división de estudios profesionales para recoger y firmar tu anexo 9.1 (formato de solicitud de acto de recepción profesional).',
        fecha: fh(d.fecha_creacion_anexo_9_1),
        completado: c9,
      },
      {
        numero: 11,
        titulo: 'Confirmación de anexo 9.1 firmado',
        detalle:
          'La DEP confirmó la recepción de tu anexo 9.1 (formato de solicitud de acto de recepción profesional) firmado.',
        fecha: fh(d.fecha_confirmacion_entrega_anexo_9_1),
        completado: c10,
      },
      {
        numero: 12,
        titulo: 'Solicitud del anexo 9.2',
        detalle:
          'Solicita en servicios escolares tu anexo 9.2 (constancia de no inconveniencia para acto de recepción profesional) y entrégalo en la DEP para continuar con el trámite.',
        fecha: fh(d.fecha_solicitud_anexo_9_2),
        completado: c11,
      },
      {
        numero: 13,
        titulo: 'Constancia 9.2 recibida',
        detalle:
          'Quedó registrada la recepción de la constancia 9.2 (constancia de no inconveniencia para acto de recepción profesional) en división de estudios profesionales.',
        fecha: fh(d.fecha_confirmacion_recibido_anexo_9_2),
        completado: c12,
      },
      {
        numero: 14,
        titulo: 'Solicitud de sinodales',
        detalle: 'La DEP solicitó al departamento académico la asignación de sinodales.',
        fecha: fh(d.fecha_solicitud_sinodales),
        completado: c13,
      },
      {
        numero: 15,
        titulo: 'Oficio de sinodales recibido',
        detalle: 'Quedó confirmada la recepción del oficio de sinodales que el departamento académico entregó a la DEP.',
        fecha: fh(d.fecha_confirmacion_sinodales_recibidos),
        completado: c14,
      },
      {
        numero: 16,
        titulo: 'Acto protocolario agendado',
        detalle: this.detalleTextoPasoActo93AgendadoAlumno(d),
        fechaDetalleResaltada: d.fecha_agenda_acto_9_3 ? fh(d.fecha_agenda_acto_9_3) : undefined,
        fecha: fh(d.fecha_agenda_acto_9_3),
        completado: c15,
      },
      {
        numero: 17,
        titulo: 'Anexo 9.3 generado',
        detalle:
          'La DEP generó el anexo 9.3 (aviso de realización de acto protocolario de titulación integral). Favor de recogerlo en división de estudios profesionales.',
        fecha: fh(d.fecha_creacion_anexo_9_3),
        completado: c16,
      },
      {
        numero: 18,
        clave: 'doc_escaneada_subir',
        titulo: 'La división de estudios profesionales solicita que subas al SITVO la documentación escaneada.',
        detalle: c17e
          ? 'Quedó registrado el envío de tu archivo PDF.'
          : c17s
            ? d.observaciones_reenvio_documentacion_escaneada?.trim()
              ? `La DEP solicitó corrección. Observaciones: ${d.observaciones_reenvio_documentacion_escaneada}`
              : 'Sube al SITVO un solo archivo .PDF con toda la documentación escaneada (no fotos).'
            : 'Cuando la división de estudios solicite la documentación, aquí podrás subirla.',
        fecha: fh(
          c17e
            ? d.fecha_envio_documentacion_escaneada_egresado
            : c17s
              ? d.fecha_solicitud_documentacion_escaneada
              : undefined,
        ),
        completado: c17e,
      },
      {
        numero: 19,
        clave: 'doc_escaneada_espera',
        titulo: 'Tu proceso por esta opción quedó concluida.',
        detalle: c17r
          ? 'La DEP confirmó la recepción de documentación escaneada.'
          : c17e
            ? 'La DEP confirmará la recepción de documentación escaneada en cuanto revise tu archivo.'
            : d.observaciones_reenvio_documentacion_escaneada?.trim()
              ? `Pendiente de nuevo envío del egresado. Observaciones: ${d.observaciones_reenvio_documentacion_escaneada}`
              : 'Cuando envíes tu archivo PDF, este paso se activará para la confirmación de la DEP.',
        fecha: fh(
          c17r
            ? d.fecha_confirmacion_documentacion_escaneada_recibida
            : c17e
              ? d.fecha_envio_documentacion_escaneada_egresado
              : undefined,
        ),
        completado: c17r,
      },
    ];
    return this.aplicarActivoPasos(raw);
  }

  get estadoActualAlumno(): string {
    const d = this.datosVista;
    if (!d) return '';
    const pasos = this.pasosAlumno;
    const p = pasos.find((x) => x.activo);
    if (p) return `Paso actual: ${p.numero}. ${p.titulo}.`;
    if (pasos.length && pasos.every((x) => x.completado)) return 'Proceso finalizado. ¡Felicidades!';
    return 'Revisa el detalle de cada paso.';
  }

  get avisoPlazosNoResAlumno(): ReturnType<typeof calcularVistaPlazosNoResidencia> | null {
    const d = this.datosVista;
    if (!d || this.esResidenciaProfesional || this.esCenevalProfesional) return null;
    return calcularVistaPlazosNoResidencia({
      modalidad: d.datos_proyecto?.modalidad,
      fecha_creacion: d.fecha_creacion,
      fecha_envio_solicitud_registro_anteproyecto_depto_academico:
        d.fecha_envio_solicitud_registro_anteproyecto_depto_academico,
      fecha_confirmacion_recepcion_inicial_anexos_xxxi_xxxii:
        d.fecha_confirmacion_recepcion_inicial_anexos_xxxi_xxxii,
      fecha_solicitud_registro_liberacion_depto_academico:
        d.fecha_solicitud_registro_liberacion_depto_academico,
      fecha_enviado_departamento_academico: d.fecha_enviado_departamento_academico,
      fecha_confirmacion_recibidos_anexo_xxxi_xxxii: d.fecha_confirmacion_recibidos_anexo_xxxi_xxxii,
      fecha_confirmacion_documentacion_escaneada_recibida: d.fecha_confirmacion_documentacion_escaneada_recibida,
    });
  }

  private plazosResidenciaAlumno() {
    const d = this.datosVista;
    if (!d) return null;
    return calcularVistaPlazosResidencia({
      fecha_registro_anexo_xxxi: undefined,
      documentos: d.documentos,
      fecha_creacion: d.fecha_creacion,
      fecha_confirmacion_documentacion_escaneada_recibida: d.fecha_confirmacion_documentacion_escaneada_recibida,
    });
  }

  get estadoAvance(): EstadoAvance {
    const d = this.datosVista;
    if (!d) return 'en_tiempo';
    if (!this.esResidenciaProfesional && !this.esCenevalProfesional) {
      return this.avisoPlazosNoResAlumno?.estadoGlobal ?? 'en_tiempo';
    }
    if (this.esCenevalProfesional) return 'en_tiempo';
    return this.plazosResidenciaAlumno()?.estadoGlobal ?? 'en_tiempo';
  }

  get estadoAvanceLabel(): string {
    if (this.estadoAvance === 'vencido') return 'Vencido';
    if (this.estadoAvance === 'rezagado') return 'Rezagado';
    return 'En tiempo';
  }

  get fechaLimiteTexto(): string {
    const d = this.datosVista;
    if (!d) return '—';
    const plazos = this.esResidenciaProfesional
      ? this.plazosResidenciaAlumno()
      : this.esCenevalProfesional
        ? null
        : this.avisoPlazosNoResAlumno;
    const lim = plazos?.fechaLimiteMasCercana;
    if (!lim) return '—';
    const dia = lim.getDate().toString().padStart(2, '0');
    const mes = (lim.getMonth() + 1).toString().padStart(2, '0');
    const anio = lim.getFullYear();
    return `${dia}/${mes}/${anio}`;
  }

  get detalleEstadoAvance(): string {
    const d = this.datosVista;
    if (!d) return '—';
    if (this.esCenevalProfesional) {
      return 'Modalidad por examen CENEVAL: sin plazo de desarrollo de proyecto; avanza según los pasos del trámite.';
    }
    const p = this.esResidenciaProfesional ? this.plazosResidenciaAlumno() : this.avisoPlazosNoResAlumno;
    if (!p) return '—';
    const dias = p.diasHastaLimiteMasCercano;
    if (dias == null) {
      return p.lineaProyecto + (p.lineaTitulacion ? ` ${p.lineaTitulacion}` : '');
    }
    if (dias < 0) {
      return `La fecha límite venció hace ${Math.abs(dias)} día(s). ${p.lineaProyecto}`;
    }
    return `Faltan ${dias} día(s) para la fecha límite. ${p.lineaProyecto}`;
  }

  get nombreCompletoEgresado(): string {
    const p = this.datos?.datos_personales;
    if (!p) return 'Egresado';
    return [p.nombre, p.apellido_paterno, p.apellido_materno].filter(Boolean).join(' ').trim() || 'Egresado';
  }

  get inicialesEgresado(): string {
    const nombre = this.nombreCompletoEgresado;
    const partes = nombre.split(/\s+/).filter(Boolean);
    if (partes.length === 0) return 'EG';
    if (partes.length === 1) return partes[0].slice(0, 2).toUpperCase();
    return (partes[0][0] + partes[1][0]).toUpperCase();
  }

  constructor(
    private egresadoService: EgresadoService,
    private catalogoService: CatalogoService,
  ) {}

  cambiarTabProceso(tab: 'actual' | number): void {
    this.tabActiva = tab;
  }

  ngOnInit(): void {
    this.cargarSeguimiento();
    this.cargarRevisionesEnviadas();
  }

  onArchivosDocEscaneadaSeleccionados(ev: Event): void {
    const input = ev.target as HTMLInputElement;
    const files = input.files ? Array.from(input.files) : [];
    const pdfs = files.filter((f) => f.type === 'application/pdf' || f.name.toLowerCase().endsWith('.pdf'));
    this.archivosPdfEscaneados = pdfs;
    this.mensajeDocEscaneada = '';
    if (files.length > pdfs.length) {
      this.mensajeDocEscaneada = 'Solo se incluyen archivos PDF; se omitieron otros formatos.';
    }
  }

  enviarDocumentacionEscaneadaAlumno(): void {
    if (this.procesoBloqueadoPorVencimientoPlazoAlumno) return;
    if (!this.archivosPdfEscaneados.length) {
      this.mensajeDocEscaneada = 'Selecciona un archivo PDF.';
      return;
    }
    this.enviandoDocEscaneada = true;
    this.mensajeDocEscaneada = '';
    this.egresadoService.subirDocumentacionEscaneadaMiSeguimiento(this.archivosPdfEscaneados).subscribe({
      next: () => {
        this.enviandoDocEscaneada = false;
        this.archivosPdfEscaneados = [];
        this.mensajeDocEscaneada = 'Documentación enviada correctamente.';
        this.cargarSeguimiento();
      },
      error: (err: { error?: { error?: string } }) => {
        this.enviandoDocEscaneada = false;
        this.mensajeDocEscaneada = err?.error?.error ?? 'No se pudo enviar la documentación. Intenta de nuevo.';
      },
    });
  }

  togglePanelRevisiones(): void {
    this.mostrarPanelRevisiones = !this.mostrarPanelRevisiones;
    if (!this.mostrarPanelRevisiones) return;
    setTimeout(() => {
      const seccion = document.getElementById('revisiones-apoyo');
      if (!seccion) return;
      seccion.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }, 0);
  }

  esRevisionExpandida(revisionId: string): boolean {
    return this.revisionesExpandidas.has(revisionId);
  }

  toggleRevision(revisionId: string): void {
    if (this.revisionesExpandidas.has(revisionId)) {
      this.revisionesExpandidas.delete(revisionId);
      return;
    }
    this.revisionesExpandidas.add(revisionId);
  }

  observacionEsLarga(obs?: string): boolean {
    return !!obs?.trim();
  }

  onArchivoCorreccionExpediente(ev: Event): void {
    const input = ev.target as HTMLInputElement;
    const file = input.files?.[0] ?? null;
    this.mensajeCorreccionExpediente = '';
    if (!file) {
      this.archivoCorreccionExpediente = null;
      return;
    }
    const ok =
      file.type === 'application/pdf' ||
      file.name.toLowerCase().endsWith('.pdf') ||
      file.type === 'application/vnd.openxmlformats-officedocument.wordprocessingml.document' ||
      file.name.toLowerCase().endsWith('.docx');
    if (!ok) {
      this.archivoCorreccionExpediente = null;
      input.value = '';
      this.mensajeCorreccionExpediente = 'Solo se permiten archivos PDF o Word (.docx).';
      return;
    }
    this.archivoCorreccionExpediente = file;
  }

  enviarExpedienteCorregido(): void {
    if (this.procesoBloqueadoPorVencimientoPlazoAlumno || !this.archivoCorreccionExpediente) return;
    this.enviandoCorreccionExpediente = true;
    this.mensajeCorreccionExpediente = '';
    this.egresadoService.reemplazarDocumentoExpedienteMiSeguimiento(this.archivoCorreccionExpediente).subscribe({
      next: () => {
        this.enviandoCorreccionExpediente = false;
        this.archivoCorreccionExpediente = null;
        this.mensajeCorreccionExpediente = 'Expediente actualizado. Coordinación verá tu archivo corregido.';
        this.cargarSeguimiento();
        this.cargarRevisionesEnviadas();
      },
      error: (err: { error?: { error?: string } }) => {
        this.enviandoCorreccionExpediente = false;
        this.mensajeCorreccionExpediente =
          err?.error?.error ?? 'No se pudo subir el archivo. Verifica que sea PDF o .docx e intenta de nuevo.';
      },
    });
  }

  descargarAdjuntoRevision(r: RevisionApi): void {
    if (!r?.id || !r.tiene_documento_adjunto) return;
    this.errorDescargaRevision = '';
    this.egresadoService.descargarDocumentoMiRevision(r.id).subscribe({
      next: ({ blob, fileName }) => {
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = fileName || `revision-${r.numero_revision}.pdf`;
        a.style.display = 'none';
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);
      },
      error: (err) => {
        const msg = err?.error?.error ?? err?.error?.message ?? err?.message ?? '';
        this.errorDescargaRevision = msg
          ? `No se pudo descargar el PDF adjunto: ${msg}`
          : 'No se pudo descargar el PDF adjunto.';
      },
    });
  }

  @HostListener('document:visibilitychange')
  onDocumentVisibilityChange(): void {
    if (document.visibilityState !== 'visible' || this.cargando) return;
    this.cargarSeguimiento({ cacheBust: true, silent: true });
  }

  cargarSeguimiento(opts?: { cacheBust?: boolean; silent?: boolean }): void {
    const cacheBust = opts?.cacheBust ?? false;
    const silent = opts?.silent ?? false;
    if (!silent) {
      this.cargando = true;
      this.error = '';
    }
    this.egresadoService.getMiSeguimiento(cacheBust).subscribe({
      next: (d) => {
        this.datos = d;
        this.cargando = false;
      },
      error: (err: { status?: number }) => {
        this.cargando = false;
        if (silent) return;
        if (err?.status === 404) {
          this.error = 'No tienes un registro de seguimiento asociado. Contacta al departamento académico.';
        } else if (err?.status === 403) {
          this.error = 'No tienes permiso para ver el seguimiento.';
        } else {
          this.error = 'No se pudo cargar el seguimiento. ¿Está el backend en ejecución?';
        }
      },
    });
  }

  private cargarRevisionesEnviadas(): void {
    this.cargandoRevisionesEnviadas = true;
    this.egresadoService.getMisRevisionesEnviadas().subscribe({
      next: (lista) => {
        this.cargandoRevisionesEnviadas = false;
        this.revisionesEnviadas = lista;
      },
      error: () => {
        this.cargandoRevisionesEnviadas = false;
        this.revisionesEnviadas = [];
      },
    });
  }

  formatearFechaHora(iso?: string): string {
    if (!iso) return '—';
    try {
      const dt = new Date(iso);
      if (isNaN(dt.getTime())) return iso;
      const dia = dt.getDate().toString().padStart(2, '0');
      const mes = (dt.getMonth() + 1).toString().padStart(2, '0');
      const anio = dt.getFullYear();
      const h = dt.getHours().toString().padStart(2, '0');
      const min = dt.getMinutes().toString().padStart(2, '0');
      return `${dia}/${mes}/${anio}, ${h}:${min}`;
    } catch {
      return iso;
    }
  }

  /** Solo fecha local dd/mm/aaaa (misma convención que el cuadro de plazo). */
  private formatearSoloFechaDesdeIso(iso: string): string {
    const dt = new Date(iso);
    if (isNaN(dt.getTime())) return iso;
    const dia = dt.getDate().toString().padStart(2, '0');
    const mes = (dt.getMonth() + 1).toString().padStart(2, '0');
    return `${dia}/${mes}/${dt.getFullYear()}`;
  }

  /** Texto breve del paso de desarrollo / recepción en división (flujo 7 pasos, no residencia). */
  private detallePasoDesarrolloAlumnoNoRes16(d: EgresadoDetail, modalidad: string): string {
    const mod = modalidad.trim() || 'titulación integral';
    const mesesDev = mesesPlazoDesarrolloProyectoNoRes(d.datos_proyecto?.modalidad);
    const plazoDevTxt = mesesDev === 18 ? '18 meses' : '12 meses';
    const liberacion = d.fecha_solicitud_registro_liberacion_depto_academico?.trim();
    if (liberacion) {
      const ini = this.formatearSoloFechaDesdeIso(liberacion);
      return `Tu departamento académico liberó la tesis el ${ini}. El plazo de desarrollo del proyecto quedó concluido. Cuentas con ${MESES_PLAZO_TITULACION_NO_RES} meses calendario desde esa fecha para avanzar en el trámite de titulación.`;
    }
    const inicial = d.fecha_confirmacion_recepcion_inicial_anexos_xxxi_xxxii?.trim();
    if (!inicial) {
      return `El plazo de ${plazoDevTxt} calendario para desarrollar tu ${mod} comenzará cuando la división confirme en la DEP la recepción del registro de tu tesis.`;
    }
    const ref = this.formatearSoloFechaDesdeIso(inicial);
    return `Tienes un plazo de ${plazoDevTxt} calendario para desarrollar tu ${mod}, contado desde el ${ref}. El plazo termina cuando tu departamento académico libere la tesis.`;
  }
}
