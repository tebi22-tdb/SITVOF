/**
 * Plazos institucionales para modalidades distintas a Residencia Profesional:
 * 12 meses calendario para el proyecto (desde envío a depto. académico o alta)
 * y 6 meses para el proceso de titulación (desde confirmación anexos XXXII/XXXIII).
 */

export const MESES_PLAZO_PROYECTO_NO_RES = 12;
export const MESES_PLAZO_TITULACION_NO_RES = 6;
export const MARGEN_REZAGO_DIAS_NO_RES = 30;

export type EstadoPlazoNoRes = 'en_tiempo' | 'rezagado' | 'vencido';

export interface FechasPlazoNoResInput {
  fecha_creacion?: string;
  fecha_enviado_departamento_academico?: string;
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

function estadoDesdeDiasRestantes(diasRest: number): EstadoPlazoNoRes {
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

/**
 * Peor estado entre fases activas (vencido > rezagado > en tiempo).
 */
function combinarEstados(a: EstadoPlazoNoRes | null, b: EstadoPlazoNoRes | null): EstadoPlazoNoRes {
  const rank: Record<EstadoPlazoNoRes, number> = { vencido: 0, rezagado: 1, en_tiempo: 2 };
  if (a == null) return b ?? 'en_tiempo';
  if (b == null) return a;
  return rank[a] <= rank[b] ? a : b;
}

export interface VistaPlazosNoResidencia {
  lineaProyecto: string;
  lineaTitulacion: string;
  estadoGlobal: EstadoPlazoNoRes;
  /** Para columna "fecha límite": la fecha fin más próxima entre fases activas */
  fechaLimiteMasCercana: Date | null;
  /** Días hasta la fecha límite más próxima (activa); null si no aplica */
  diasHastaLimiteMasCercano: number | null;
}

export function calcularVistaPlazosNoResidencia(
  d: FechasPlazoNoResInput,
  hoy: Date = new Date(),
): VistaPlazosNoResidencia {
  const inicioPro =
    parseIso(d.fecha_enviado_departamento_academico) ?? parseIso(d.fecha_creacion);
  const confAnexos = parseIso(d.fecha_confirmacion_recibidos_anexo_xxxi_xxxii);
  const docCerrado = !!d.fecha_confirmacion_documentacion_escaneada_recibida;

  const proyectoConcluido = !!confAnexos;
  const titulacionConcluido = docCerrado;

  let lineaProyecto: string;
  let estPro: EstadoPlazoNoRes | null = null;
  let finPro: Date | null = null;
  let diasPro: number | null = null;

  if (proyectoConcluido) {
    lineaProyecto = `Proyecto: etapa concluida (plazo de ${MESES_PLAZO_PROYECTO_NO_RES} meses superado).`;
  } else if (!inicioPro) {
    lineaProyecto = `Proyecto: pendiente registrar el envío al departamento académico para contar el plazo de ${MESES_PLAZO_PROYECTO_NO_RES} meses.`;
    estPro = 'en_tiempo';
  } else {
    finPro = sumarMesesCalendario(inicioPro, MESES_PLAZO_PROYECTO_NO_RES);
    diasPro = diffDiasCalendario(finPro, hoy);
    estPro = estadoDesdeDiasRestantes(diasPro);
    lineaProyecto = `Proyecto: ${mesesRestantesTexto(diasPro, MESES_PLAZO_PROYECTO_NO_RES)}.`;
  }

  let lineaTitulacion: string;
  let estTit: EstadoPlazoNoRes | null = null;
  let finTit: Date | null = null;
  let diasTit: number | null = null;

  if (titulacionConcluido) {
    lineaTitulacion = `Proceso de titulación: concluido en el sistema.`;
  } else if (!confAnexos) {
    lineaTitulacion = `Proceso de titulación: aún no inicia (cuenta desde la confirmación de anexos XXXII y XXXIII); plazo de ${MESES_PLAZO_TITULACION_NO_RES} meses.`;
    estTit = null;
  } else {
    finTit = sumarMesesCalendario(confAnexos, MESES_PLAZO_TITULACION_NO_RES);
    diasTit = diffDiasCalendario(finTit, hoy);
    estTit = estadoDesdeDiasRestantes(diasTit);
    lineaTitulacion = `Proceso de titulación: ${mesesRestantesTexto(diasTit, MESES_PLAZO_TITULACION_NO_RES)}.`;
  }

  let estadoGlobal = combinarEstados(estPro, estTit);

  let fechaLimiteMasCercana: Date | null = null;
  let diasHastaLimiteMasCercano: number | null = null;

  const candidatos: { fin: Date; dias: number }[] = [];
  if (!proyectoConcluido && inicioPro && finPro != null && diasPro != null) {
    candidatos.push({ fin: finPro, dias: diasPro });
  }
  if (!titulacionConcluido && confAnexos && finTit != null && diasTit != null) {
    candidatos.push({ fin: finTit, dias: diasTit });
  }
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
