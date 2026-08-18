/**
 * Clasifica en qué etapa real del proceso de titulación está un expediente,
 * a partir de las fechas que ya trae el listado (sin pedir el detalle
 * completo egresado por egresado).
 *
 * El orden de cada flujo replica el que arma
 * seguimiento-proceso.component.ts (construirPasosSeguimiento*) para la
 * vista de detalle — si ese orden cambia allá, hay que actualizarlo aquí
 * también. Se separó a "solo claves + etapa" porque el detalle necesita
 * además títulos/descripciones que solo tienen sentido ahí.
 *
 * OJO con 'fecha_enviado_departamento_academico': el mismo campo de Mongo
 * significa cosas distintas según el flujo — en Residencia/CENEVAL/Legacy es
 * el primer paso (envío inicial a departamento), pero en el flujo de 16
 * pasos (no residencia) es el séptimo (envío a Coordinación para revisión de
 * normas, después de que el expediente ya pasó dos veces por departamento).
 * Por eso la clasificación no puede ser "¿tiene este campo?" a secas: tiene
 * que saber primero en qué flujo está el expediente.
 */
import type { EgresadoItem } from '../services/egresado.service';

export type EtapaTitulacion =
  | 'registro'
  | 'departamento'
  | 'desarrollo_proyecto'
  | 'revision_normas'
  | 'anexos_servicios_escolares'
  | 'sinodales'
  | 'acto_protocolario'
  | 'documentacion_escaneada'
  | 'concluido';

export const ORDEN_ETAPAS_TITULACION: EtapaTitulacion[] = [
  'registro',
  'departamento',
  'desarrollo_proyecto',
  'revision_normas',
  'anexos_servicios_escolares',
  'sinodales',
  'acto_protocolario',
  'documentacion_escaneada',
  'concluido',
];

export const ETAPA_TITULACION_LABEL: Record<EtapaTitulacion, string> = {
  registro: 'Registro / envío a departamento',
  departamento: 'En departamento académico',
  desarrollo_proyecto: 'Desarrollo del proyecto',
  revision_normas: 'Revisión de normas (Coordinación)',
  anexos_servicios_escolares: 'Anexos 9.1/9.2 (Servicios Escolares)',
  sinodales: 'Sinodales',
  acto_protocolario: 'Acto protocolario',
  documentacion_escaneada: 'Documentación escaneada',
  concluido: 'Concluido',
};

interface PasoClave {
  key: keyof EgresadoItem;
  etapa: EtapaTitulacion;
}

/** Cola común a los 4 flujos: anexos 9.1/9.2, sinodales, acto y doc. escaneada. */
const COLA_COMUN: PasoClave[] = [
  { key: 'fecha_creacion_anexo_9_1',                          etapa: 'anexos_servicios_escolares' },
  { key: 'fecha_confirmacion_entrega_anexo_9_1',              etapa: 'anexos_servicios_escolares' },
  { key: 'fecha_solicitud_anexo_9_2',                         etapa: 'anexos_servicios_escolares' },
  { key: 'fecha_aceptacion_servicios_escolares_anexo_9_2',    etapa: 'anexos_servicios_escolares' },
  { key: 'fecha_confirmacion_recibido_anexo_9_2',             etapa: 'anexos_servicios_escolares' },
  { key: 'fecha_solicitud_sinodales',                         etapa: 'sinodales' },
  { key: 'fecha_confirmacion_sinodales_recibidos',            etapa: 'sinodales' },
  { key: 'fecha_agenda_acto_9_3',                             etapa: 'acto_protocolario' },
  { key: 'fecha_creacion_anexo_9_3',                          etapa: 'acto_protocolario' },
  { key: 'fecha_confirmacion_entrega_anexo_9_3',              etapa: 'acto_protocolario' },
  { key: 'fecha_solicitud_documentacion_escaneada',           etapa: 'documentacion_escaneada' },
  { key: 'fecha_confirmacion_documentacion_escaneada_recibida', etapa: 'concluido' },
];

const FLUJO_RESIDENCIA: PasoClave[] = [
  { key: 'fecha_enviado_departamento_academico',          etapa: 'departamento' },
  { key: 'fecha_confirmacion_recibidos_anexo_xxxi_xxxii', etapa: 'departamento' },
  ...COLA_COMUN,
];

const FLUJO_CENEVAL: PasoClave[] = [
  { key: 'fecha_confirmacion_entrega_egresado_depto', etapa: 'departamento' },
  ...COLA_COMUN,
];

const FLUJO_NO_RESIDENCIA_LEGACY: PasoClave[] = [
  { key: 'fecha_enviado_departamento_academico',          etapa: 'departamento' },
  { key: 'fecha_confirmacion_recibidos_anexo_xxxi_xxxii', etapa: 'departamento' },
  ...COLA_COMUN,
];

const FLUJO_NO_RESIDENCIA_16: PasoClave[] = [
  { key: 'fecha_confirmacion_entrega_egresado_depto',                   etapa: 'departamento' },
  { key: 'fecha_envio_solicitud_registro_anteproyecto_depto_academico', etapa: 'departamento' },
  { key: 'fecha_confirmacion_recepcion_inicial_anexos_xxxi_xxxii',      etapa: 'departamento' },
  { key: 'fecha_recepcion_trabajo_division_estudios_prof',              etapa: 'desarrollo_proyecto' },
  { key: 'fecha_solicitud_registro_liberacion_depto_academico',         etapa: 'departamento' }, // segunda vuelta: liberación
  { key: 'fecha_recepcion_registro_liberacion_depto_academico',         etapa: 'departamento' },
  { key: 'fecha_enviado_departamento_academico',                       etapa: 'revision_normas' }, // ojo: mismo campo, otro significado aquí
  ...COLA_COMUN,
];

/** Mismo criterio que seguimiento-proceso.noResidenciaFlujoLegacy: ya tiene
 *  envío a departamento pero nunca inició el flujo de 16 pasos (que arranca
 *  con la solicitud de registro de anteproyecto). */
function esNoResidenciaLegacy(item: EgresadoItem): boolean {
  return !!item.fecha_enviado_departamento_academico && !item.fecha_envio_solicitud_registro_anteproyecto_depto_academico;
}

/** `esResidencia`/`esCeneval` se calculan afuera (con CatalogoService) para
 *  que este archivo se quede como función pura, sin depender de Angular. */
export function etapaActualDe(item: EgresadoItem, esResidencia: boolean, esCeneval: boolean): EtapaTitulacion {
  let flujo: PasoClave[];
  if (esResidencia) flujo = FLUJO_RESIDENCIA;
  else if (esCeneval) flujo = FLUJO_CENEVAL;
  else flujo = esNoResidenciaLegacy(item) ? FLUJO_NO_RESIDENCIA_LEGACY : FLUJO_NO_RESIDENCIA_16;

  let ultimaEtapa: EtapaTitulacion = 'registro';
  for (const paso of flujo) {
    if (item[paso.key]) ultimaEtapa = paso.etapa;
  }
  return ultimaEtapa;
}
