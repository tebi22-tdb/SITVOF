/**
 * Plazos para lista / semáforo del coordinador y vista del egresado.
 * - Residencia: 6 meses desde fecha de registro Anexo XXXI (alta).
 * - No residencia (flujo 7 pasos): desarrollo 12 meses desde confirmación DEP paso 3;
 *   tras liberación en departamento, 6 meses hasta envío a CAT.
 */
import {
  calcularVistaPlazoDesarrolloRecepcionNoRes,
  mesesPlazoDesarrolloProyectoNoRes,
  type EgresadoPlazoRecepcionInput,
} from './plazo-desarrollo-proyecto-no-res';

export const MESES_PLAZO_PROYECTO_NO_RES = 12;
export const MESES_PLAZO_TITULACION_NO_RES = 6;
export const MARGEN_REZAGO_DIAS_NO_RES = 30;
export const MESES_PLAZO_RESIDENCIA = 6;

export type EstadoPlazoNoRes = 'en_tiempo' | 'rezagado' | 'vencido';

export interface FechasPlazoNoResInput {
  modalidad?: string;
  fecha_creacion?: string;
  fecha_registro_anexo_xxxi?: string;
  documentos?: { anexo_xxxi?: { fecha_registro?: string | null } };
  fecha_envio_solicitud_registro_anteproyecto_depto_academico?: string;
  fecha_confirmacion_recepcion_inicial_anexos_xxxi_xxxii?: string;
  fecha_solicitud_registro_liberacion_depto_academico?: string;
  fecha_recepcion_trabajo_division_estudios_prof?: string;
  fecha_enviado_departamento_academico?: string;
  /** Flujo legacy (CAT): confirmación anexos XXXII/XXXIII */
  fecha_confirmacion_recibidos_anexo_xxxi_xxxii?: string;
  fecha_confirmacion_documentacion_escaneada_recibida?: string;
}

function inicioDiaLocal(d: Date): Date {
  return new Date(d.getFullYear(), d.getMonth(), d.getDate());
}

function diffDiasCalendario(fechaFin: Date, fechaInicio: Date): number {
  const ms = inicioDiaLocal(fechaFin).getTime() - inicioDiaLocal(fechaInicio).getTime();
  return Math.round(ms / 86400000);
}

function parseIso(s?: string | null): Date | null {
  if (!s?.trim()) return null;
  const d = new Date(s);
  return isNaN(d.getTime()) ? null : d;
}

function sumarMesesCalendario(base: Date, meses: number): Date {
  const d = new Date(base.getFullYear(), base.getMonth(), base.getDate());
  d.setMonth(d.getMonth() + meses);
  return d;
}

export function estadoDesdeDiasRestantesPlazo(diasRest: number): EstadoPlazoNoRes {
  if (diasRest < 0) return 'vencido';
  if (diasRest <= MARGEN_REZAGO_DIAS_NO_RES) return 'rezagado';
  return 'en_tiempo';
}

function mesesRestantesTexto(diasRest: number, maxMeses: number): string {
  if (diasRest < 0) {
    const m = Math.ceil(-diasRest / 30.4375);
    return `plazo vencido (aprox. ${m} mes(es) de retraso)`;
  }
  const m = Math.min(maxMeses, Math.max(0, Math.ceil(diasRest / 30.4375)));
  return `${m} mes(es) restantes de ${maxMeses}`;
}

export function esModalidadNoResidencia(modalidad: string | undefined | null): boolean {
  return (modalidad ?? '').trim().toLowerCase() !== 'residencia profesional';
}

/** Residencia: día 1 del plazo = fecha de registro Anexo XXXI al alta; respaldo creación del trámite. */
export function inicioPlazoIsoResidencia(opts: {
  fecha_registro_anexo_xxxi?: string | null;
  documentos?: { anexo_xxxi?: { fecha_registro?: string | null } };
  fecha_creacion?: string | null;
}): string | null {
  const desdeLista = opts.fecha_registro_anexo_xxxi?.trim();
  if (desdeLista) return desdeLista;
  const desdeDetalle = opts.documentos?.anexo_xxxi?.fecha_registro?.trim();
  if (desdeDetalle) return desdeDetalle;
  return opts.fecha_creacion?.trim() || null;
}

function esFlujo7PasosNoRes(d: FechasPlazoNoResInput): boolean {
  return !!(
    d.fecha_envio_solicitud_registro_anteproyecto_depto_academico?.trim() ||
    d.fecha_confirmacion_recepcion_inicial_anexos_xxxi_xxxii?.trim()
  );
}

export interface VistaPlazosNoResidencia {
  lineaProyecto: string;
  lineaTitulacion: string;
  estadoGlobal: EstadoPlazoNoRes;
  fechaLimiteMasCercana: Date | null;
  diasHastaLimiteMasCercano: number | null;
}

/** Residencia: lista y egresado (6 meses desde Anexo XXXI). */
export function calcularVistaPlazosResidencia(
  d: FechasPlazoNoResInput,
  hoy: Date = new Date(),
): VistaPlazosNoResidencia {
  if (d.fecha_confirmacion_documentacion_escaneada_recibida?.trim()) {
    return {
      lineaProyecto: 'Residencia: proceso concluido en el sistema.',
      lineaTitulacion: '',
      estadoGlobal: 'en_tiempo',
      fechaLimiteMasCercana: null,
      diasHastaLimiteMasCercano: null,
    };
  }
  const iso = inicioPlazoIsoResidencia(d);
  if (!iso) {
    return {
      lineaProyecto: 'Residencia: pendiente fecha de registro del Anexo XXXI para iniciar el plazo de 6 meses.',
      lineaTitulacion: '',
      estadoGlobal: 'en_tiempo',
      fechaLimiteMasCercana: null,
      diasHastaLimiteMasCercano: null,
    };
  }
  const inicio = parseIso(iso)!;
  const fin = sumarMesesCalendario(inicio, MESES_PLAZO_RESIDENCIA);
  const dias = diffDiasCalendario(fin, hoy);
  const estado = estadoDesdeDiasRestantesPlazo(dias);
  return {
    lineaProyecto: `Residencia: ${mesesRestantesTexto(dias, MESES_PLAZO_RESIDENCIA)} (desde Anexo XXXI).`,
    lineaTitulacion: '',
    estadoGlobal: estado,
    fechaLimiteMasCercana: fin,
    diasHastaLimiteMasCercano: dias,
  };
}

/** No residencia flujo 7 pasos (misma lógica que el cuadro verde del paso desarrollo). */
function calcularVistaPlazosNoResFlujo7(
  d: FechasPlazoNoResInput,
  hoy: Date = new Date(),
): VistaPlazosNoResidencia {
  if (d.fecha_confirmacion_documentacion_escaneada_recibida?.trim()) {
    return {
      lineaProyecto: 'Proceso concluido en el sistema.',
      lineaTitulacion: '',
      estadoGlobal: 'en_tiempo',
      fechaLimiteMasCercana: null,
      diasHastaLimiteMasCercano: null,
    };
  }
  if (d.fecha_enviado_departamento_academico?.trim()) {
    return {
      lineaProyecto: 'Desarrollo y liberación concluidos; expediente en revisión (Apoyo a Titulación).',
      lineaTitulacion: '',
      estadoGlobal: 'en_tiempo',
      fechaLimiteMasCercana: null,
      diasHastaLimiteMasCercano: null,
    };
  }
  const input: EgresadoPlazoRecepcionInput = {
    datos_proyecto: { modalidad: d.modalidad },
    fecha_envio_solicitud_registro_anteproyecto_depto_academico:
      d.fecha_envio_solicitud_registro_anteproyecto_depto_academico,
    fecha_confirmacion_recepcion_inicial_anexos_xxxi_xxxii:
      d.fecha_confirmacion_recepcion_inicial_anexos_xxxi_xxxii,
    fecha_recepcion_trabajo_division_estudios_prof: d.fecha_recepcion_trabajo_division_estudios_prof,
    fecha_solicitud_registro_liberacion_depto_academico: d.fecha_solicitud_registro_liberacion_depto_academico,
    fecha_enviado_departamento_academico: d.fecha_enviado_departamento_academico,
  };
  if (!d.fecha_confirmacion_recepcion_inicial_anexos_xxxi_xxxii?.trim()) {
    return {
      lineaProyecto:
        'Pendiente que la DEP confirme el registro de la tesis (paso 3) para iniciar el plazo de desarrollo.',
      lineaTitulacion: '',
      estadoGlobal: 'en_tiempo',
      fechaLimiteMasCercana: null,
      diasHastaLimiteMasCercano: null,
    };
  }
  const raw = calcularVistaPlazoDesarrolloRecepcionNoRes(input);
  if (!raw) {
    return {
      lineaProyecto: 'Plazo de desarrollo no aplica en esta etapa.',
      lineaTitulacion: '',
      estadoGlobal: 'en_tiempo',
      fechaLimiteMasCercana: null,
      diasHastaLimiteMasCercano: null,
    };
  }
  const mesesDev = mesesPlazoDesarrolloProyectoNoRes(d.modalidad);
  const dias = diffDiasCalendario(raw.fechaLimite, hoy);
  const linea = raw.fasePostRecepcion
    ? `Trámite tras liberación: ${mesesRestantesTexto(dias, MESES_PLAZO_TITULACION_NO_RES)} (${MESES_PLAZO_TITULACION_NO_RES} meses desde liberación en departamento).`
    : `Desarrollo de tesis: ${mesesRestantesTexto(dias, raw.mesesTotales)} (${mesesDev} meses desde confirmación DEP paso 3).`;
  return {
    lineaProyecto: linea,
    lineaTitulacion: '',
    estadoGlobal: raw.estado,
    fechaLimiteMasCercana: raw.fechaLimite,
    diasHastaLimiteMasCercano: dias,
  };
}

/** Expedientes antiguos (sin flujo 7): envío a CAT + 6 meses titulación. */
function calcularVistaPlazosNoResLegacy(
  d: FechasPlazoNoResInput,
  hoy: Date = new Date(),
): VistaPlazosNoResidencia {
  const inicioPro = parseIso(d.fecha_enviado_departamento_academico) ?? parseIso(d.fecha_creacion);
  const confAnexos = parseIso(d.fecha_confirmacion_recibidos_anexo_xxxi_xxxii);
  const docCerrado = !!d.fecha_confirmacion_documentacion_escaneada_recibida?.trim();

  const proyectoConcluido = !!confAnexos;
  const titulacionConcluido = docCerrado;

  let lineaProyecto: string;
  let estPro: EstadoPlazoNoRes | null = null;
  let finPro: Date | null = null;
  let diasPro: number | null = null;

  if (proyectoConcluido) {
    lineaProyecto = `Proyecto: etapa concluida (plazo de ${MESES_PLAZO_PROYECTO_NO_RES} meses superado).`;
  } else if (!inicioPro) {
    lineaProyecto = `Proyecto: pendiente envío al departamento académico para contar ${MESES_PLAZO_PROYECTO_NO_RES} meses.`;
    estPro = 'en_tiempo';
  } else {
    finPro = sumarMesesCalendario(inicioPro, MESES_PLAZO_PROYECTO_NO_RES);
    diasPro = diffDiasCalendario(finPro, hoy);
    estPro = estadoDesdeDiasRestantesPlazo(diasPro);
    lineaProyecto = `Proyecto: ${mesesRestantesTexto(diasPro, MESES_PLAZO_PROYECTO_NO_RES)}.`;
  }

  let lineaTitulacion: string;
  let estTit: EstadoPlazoNoRes | null = null;
  let finTit: Date | null = null;
  let diasTit: number | null = null;

  if (titulacionConcluido) {
    lineaTitulacion = 'Proceso de titulación: concluido en el sistema.';
  } else if (!confAnexos) {
    lineaTitulacion = `Titulación: inicia al confirmar anexos XXXII/XXXIII (${MESES_PLAZO_TITULACION_NO_RES} meses).`;
    estTit = null;
  } else {
    finTit = sumarMesesCalendario(confAnexos, MESES_PLAZO_TITULACION_NO_RES);
    diasTit = diffDiasCalendario(finTit, hoy);
    estTit = estadoDesdeDiasRestantesPlazo(diasTit);
    lineaTitulacion = `Titulación: ${mesesRestantesTexto(diasTit, MESES_PLAZO_TITULACION_NO_RES)}.`;
  }

  const rank: Record<EstadoPlazoNoRes, number> = { vencido: 0, rezagado: 1, en_tiempo: 2 };
  let estadoGlobal: EstadoPlazoNoRes = 'en_tiempo';
  for (const e of [estPro, estTit]) {
    if (e != null && rank[e] < rank[estadoGlobal]) estadoGlobal = e;
  }

  const candidatos: { fin: Date; dias: number }[] = [];
  if (!proyectoConcluido && inicioPro && finPro != null && diasPro != null) {
    candidatos.push({ fin: finPro, dias: diasPro });
  }
  if (!titulacionConcluido && confAnexos && finTit != null && diasTit != null) {
    candidatos.push({ fin: finTit, dias: diasTit });
  }
  let fechaLimiteMasCercana: Date | null = null;
  let diasHastaLimiteMasCercano: number | null = null;
  if (candidatos.length > 0) {
    candidatos.sort((x, y) => x.dias - y.dias);
    fechaLimiteMasCercana = candidatos[0].fin;
    diasHastaLimiteMasCercano = candidatos[0].dias;
  }
  if (proyectoConcluido && !confAnexos) {
    estadoGlobal = estPro ?? 'en_tiempo';
  }

  return {
    lineaProyecto,
    lineaTitulacion,
    estadoGlobal,
    fechaLimiteMasCercana,
    diasHastaLimiteMasCercano,
  };
}

export function calcularVistaPlazosNoResidencia(
  d: FechasPlazoNoResInput,
  hoy: Date = new Date(),
): VistaPlazosNoResidencia {
  if (esFlujo7PasosNoRes(d)) {
    return calcularVistaPlazosNoResFlujo7(d, hoy);
  }
  return calcularVistaPlazosNoResLegacy(d, hoy);
}
