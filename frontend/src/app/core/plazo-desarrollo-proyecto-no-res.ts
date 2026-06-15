/**
 * No residencia, flujo 7 pasos (tesis):
 * - Plazo de desarrollo (12 meses): desde la confirmación DEP del paso 3 (recepción XXXI, anteproyecto y XXXII),
 *   no desde el envío al departamento académico (paso 2).
 * - Tras confirmar recepción del trabajo en división (paso 4): termina el plazo de desarrollo; 6 meses calendario
 *   para el trámite de titulación posterior.
 * Semáforo respecto a la fecha límite con margen de rezago antes del vencimiento.
 */
import { MARGEN_REZAGO_DIAS_NO_RES, MESES_PLAZO_TITULACION_NO_RES } from './plazos-titulacion-no-residencia';

export type EstadoPlazoDesarrolloDiv = 'en_tiempo' | 'rezagado' | 'vencido';

export interface PlazoDesarrolloRecepcionUi {
  estado: EstadoPlazoDesarrolloDiv;
  etiquetaEstado: string;
  claseSemafono: 'plazo-desarrollo-rec--ok' | 'plazo-desarrollo-rec--warn' | 'plazo-desarrollo-rec--bad';
  fechaInicioTexto: string;
  etiquetaLineaInicio: string;
  mesesTotales: number;
  /** true = plazo de 6 meses desde recepción del trabajo en división. */
  fasePostRecepcion: boolean;
  fechaLimiteTexto: string;
  /** Resumen técnico (coordinador). */
  textoMesesRestantes: string;
  /** Una línea: cuánto falta hasta el límite (alumno). */
  textoTiempoRestanteCorto: string;
}

export function mesesPlazoDesarrolloProyectoNoRes(modalidad: string | undefined | null): number {
  return 12;
}

function inicioDiaLocal(d: Date): Date {
  return new Date(d.getFullYear(), d.getMonth(), d.getDate());
}

function diffDiasCalendario(fechaFin: Date, fechaInicio: Date): number {
  const ms = inicioDiaLocal(fechaFin).getTime() - inicioDiaLocal(fechaInicio).getTime();
  return Math.round(ms / 86400000);
}

export function sumarMesesCalendarioDesarrollo(base: Date, meses: number): Date {
  return new Date(base.getFullYear(), base.getMonth() + meses, base.getDate());
}

function estadoDesdeDiasRestantes(diasRest: number): EstadoPlazoDesarrolloDiv {
  if (diasRest < 0) return 'vencido';
  if (diasRest <= MARGEN_REZAGO_DIAS_NO_RES) return 'rezagado';
  return 'en_tiempo';
}

export function etiquetaEstadoPlazoDesarrollo(e: EstadoPlazoDesarrolloDiv): string {
  if (e === 'vencido') return 'Vencido';
  if (e === 'rezagado') return 'Rezagado';
  return 'En tiempo';
}

export interface PlazoDesarrolloProyectoVista {
  estado: EstadoPlazoDesarrolloDiv;
  fechaInicio: Date;
  usaInicioProvisionalDesdeEnvio: boolean;
  /** true = límite de MESES_PLAZO_TITULACION_NO_RES desde recepción en división. */
  fasePostRecepcion: boolean;
  mesesTotales: number;
  fechaLimite: Date;
  diasHastaLimite: number;
  mesesRestantesCompletos: number;
  diasExtraDespuesDeMesesCompletos: number;
}

export function calcularPlazoDesarrolloProyectoNoRes(opts: {
  modalidad: string | undefined | null;
  fechaRecepcionDivision?: string | null;
  /** Liberación de producto en departamento: cierra plazo de desarrollo e inicia fase de 6 meses. */
  fechaLiberacionProducto?: string | null;
  fechaEnvioAnteproyecto?: string | null;
  /** Confirmación DEP paso 3: inicio del plazo de desarrollo 12/18 meses. */
  fechaConfirmacionInicial?: string | null;
  hoy?: Date;
}): PlazoDesarrolloProyectoVista | null {
  const liberacion = opts.fechaLiberacionProducto?.trim();
  const recep = opts.fechaRecepcionDivision?.trim();
  const finDesarrollo = liberacion || recep;
  const inicial = opts.fechaConfirmacionInicial?.trim();

  let fechaInicio: Date;
  let mesesTotales: number;
  let usaInicioProvisionalDesdeEnvio: boolean;
  let fasePostRecepcion: boolean;

  if (finDesarrollo) {
    fechaInicio = new Date(finDesarrollo);
    if (isNaN(fechaInicio.getTime())) return null;
    mesesTotales = MESES_PLAZO_TITULACION_NO_RES;
    usaInicioProvisionalDesdeEnvio = false;
    fasePostRecepcion = true;
  } else {
    const inicioIso = inicial?.trim();
    if (!inicioIso) return null;
    fechaInicio = new Date(inicioIso);
    if (isNaN(fechaInicio.getTime())) return null;
    mesesTotales = mesesPlazoDesarrolloProyectoNoRes(opts.modalidad);
    usaInicioProvisionalDesdeEnvio = false;
    fasePostRecepcion = false;
  }

  const hoy = inicioDiaLocal(opts.hoy ?? new Date());
  const inicio = inicioDiaLocal(fechaInicio);
  const fechaLimite = sumarMesesCalendarioDesarrollo(inicio, mesesTotales);
  const diasHastaLimite = diffDiasCalendario(fechaLimite, hoy);
  const estado = estadoDesdeDiasRestantes(diasHastaLimite);

  let mesesRestantesCompletos = 0;
  let diasExtraDespuesDeMesesCompletos = 0;
  if (diasHastaLimite >= 0) {
    for (;;) {
      const next = sumarMesesCalendarioDesarrollo(hoy, mesesRestantesCompletos + 1);
      if (next > inicioDiaLocal(fechaLimite)) break;
      mesesRestantesCompletos++;
    }
    const fechaTrasM = sumarMesesCalendarioDesarrollo(hoy, mesesRestantesCompletos);
    diasExtraDespuesDeMesesCompletos = Math.max(0, diffDiasCalendario(inicioDiaLocal(fechaLimite), fechaTrasM));
  }

  return {
    estado,
    fechaInicio,
    usaInicioProvisionalDesdeEnvio,
    fasePostRecepcion,
    mesesTotales,
    fechaLimite,
    diasHastaLimite,
    mesesRestantesCompletos,
    diasExtraDespuesDeMesesCompletos,
  };
}

function formatearFechaLocalDdMmYyyy(d: Date): string {
  const dia = d.getDate().toString().padStart(2, '0');
  const mes = (d.getMonth() + 1).toString().padStart(2, '0');
  return `${dia}/${mes}/${d.getFullYear()}`;
}

function descripcionMesesRestantes(v: PlazoDesarrolloProyectoVista): string {
  if (v.diasHastaLimite < 0) {
    return `Plazo vencido (${Math.abs(v.diasHastaLimite)} día(s) de retraso respecto al límite del periodo).`;
  }
  const parts: string[] = [];
  if (v.mesesRestantesCompletos > 0) {
    parts.push(`${v.mesesRestantesCompletos} mes${v.mesesRestantesCompletos !== 1 ? 'es' : ''}`);
  }
  if (v.diasExtraDespuesDeMesesCompletos > 0) {
    parts.push(`${v.diasExtraDespuesDeMesesCompletos} día${v.diasExtraDespuesDeMesesCompletos !== 1 ? 's' : ''}`);
  }
  if (parts.length === 0) {
    return 'Último día del plazo (0 meses restantes en conteo por meses calendario).';
  }
  return `${parts.join(' y ')} restantes hasta el límite del periodo (${v.mesesTotales} mes${v.mesesTotales !== 1 ? 'es' : ''} calendario desde la fecha de inicio).`;
}

function textoTiempoRestanteCorto(v: PlazoDesarrolloProyectoVista): string {
  const lim = formatearFechaLocalDdMmYyyy(v.fechaLimite);
  if (v.diasHastaLimite < 0) {
    return `Plazo vencido: el límite era el ${lim} (llevas ${Math.abs(v.diasHastaLimite)} día(s) de retraso).`;
  }
  const parts: string[] = [];
  if (v.mesesRestantesCompletos > 0) {
    parts.push(`${v.mesesRestantesCompletos} mes${v.mesesRestantesCompletos !== 1 ? 'es' : ''}`);
  }
  if (v.diasExtraDespuesDeMesesCompletos > 0) {
    parts.push(`${v.diasExtraDespuesDeMesesCompletos} día${v.diasExtraDespuesDeMesesCompletos !== 1 ? 's' : ''}`);
  }
  const tiempo = parts.length ? parts.join(' y ') : 'menos de un día';
  return `Te falta: ${tiempo} para llegar al límite (${lim}).`;
}

export interface EgresadoPlazoRecepcionInput {
  datos_proyecto?: { modalidad?: string; curso_titulacion?: string };
  documentos?: { constancia_no_inconveniencia?: { fecha_expedicion?: string } };
  fecha_envio_solicitud_registro_anteproyecto_depto_academico?: string;
  fecha_confirmacion_recepcion_inicial_anexos_xxxi_xxxii?: string;
  fecha_recepcion_trabajo_division_estudios_prof?: string;
  fecha_solicitud_registro_liberacion_depto_academico?: string;
  /** Tras envío a CAT deja de aplicar esta vista de plazo (no residencia). */
  fecha_enviado_departamento_academico?: string;
}

/**
 * Vista numérica del plazo (desarrollo 12 meses desde inicio según modalidad).
 * Para curso de titulación: inicia desde la fecha de expedición de la constancia de no inconveniencia.
 * Para demás modalidades: inicia desde confirmación DEP paso 3 (Anexo XXXII).
 */
export function calcularVistaPlazoDesarrolloRecepcionNoRes(
  d: EgresadoPlazoRecepcionInput,
): PlazoDesarrolloProyectoVista | null {
  if (!d.fecha_envio_solicitud_registro_anteproyecto_depto_academico?.trim()) return null;
  if (d.fecha_enviado_departamento_academico?.trim()) return null;
  const esCurso = d.datos_proyecto?.curso_titulacion === 'si';
  const fechaInicio = esCurso
    ? d.documentos?.constancia_no_inconveniencia?.fecha_expedicion
    : d.fecha_confirmacion_recepcion_inicial_anexos_xxxi_xxxii;
  return calcularPlazoDesarrolloProyectoNoRes({
    modalidad: d.datos_proyecto?.modalidad,
    fechaRecepcionDivision: d.fecha_recepcion_trabajo_division_estudios_prof,
    fechaLiberacionProducto: d.fecha_solicitud_registro_liberacion_depto_academico,
    fechaEnvioAnteproyecto: d.fecha_envio_solicitud_registro_anteproyecto_depto_academico.trim(),
    fechaConfirmacionInicial: fechaInicio,
  });
}

/**
 * UI del cuadro verde en paso 4 (desarrollo): corre desde confirmación DEP paso 3 hasta liberación en departamento.
 */
export function construirPlazoDesarrolloRecepcionUi(d: EgresadoPlazoRecepcionInput): PlazoDesarrolloRecepcionUi | null {
  if (d.fecha_solicitud_registro_liberacion_depto_academico?.trim()) return null;
  if (!d.fecha_confirmacion_recepcion_inicial_anexos_xxxi_xxxii?.trim()) return null;
  const envio = d.fecha_envio_solicitud_registro_anteproyecto_depto_academico?.trim();
  if (!envio) return null;
  const raw = calcularVistaPlazoDesarrolloRecepcionNoRes(d);
  if (!raw) return null;
  const claseSemafono: PlazoDesarrolloRecepcionUi['claseSemafono'] =
    raw.estado === 'vencido'
      ? 'plazo-desarrollo-rec--bad'
      : raw.estado === 'rezagado'
        ? 'plazo-desarrollo-rec--warn'
        : 'plazo-desarrollo-rec--ok';
  const etiquetaLineaInicio = raw.usaInicioProvisionalDesdeEnvio
    ? 'Fecha de inicio del conteo'
    : 'Fecha de inicio del plazo';
  const fechaLimiteTexto = formatearFechaLocalDdMmYyyy(raw.fechaLimite);
  return {
    estado: raw.estado,
    etiquetaEstado: etiquetaEstadoPlazoDesarrollo(raw.estado),
    claseSemafono,
    fechaInicioTexto: formatearFechaLocalDdMmYyyy(raw.fechaInicio),
    etiquetaLineaInicio,
    mesesTotales: raw.mesesTotales,
    fasePostRecepcion: raw.fasePostRecepcion,
    fechaLimiteTexto,
    textoMesesRestantes: descripcionMesesRestantes(raw),
    textoTiempoRestanteCorto: textoTiempoRestanteCorto(raw),
  };
}
