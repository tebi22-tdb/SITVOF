/**
 * Constantes e interfaces compartidas del SITVO.
 * Carreras, niveles, modalidades y modelo del formulario de egresado.
 */

export const CARRERAS = [
  'INGENIERIA EN AGRONOMÍA',
  'LICENCIATURA EN BIOLOGÍA',
  'INGENIERIA FORESTAL',
  'INGENIERIA INFORMÁTICA',
  'INGENIERIA EN TECNOLOGIA DE LA INFORMACION Y COMUNICACION ',
  'INGENIERIA EN CIENCIA DE DATOS',
  'INGENIERIA SISTEMAS COMPUTACIONALES',
  'INGENIERIA AMBIENTAL',
  'INGENIERIA EN GESTIÓN EMPRESARIAL (VIRTUAL)',
] as const;

export const NIVELES = ['Licenciatura', 'Maestría', 'Posgrado'] as const;

export const MODALIDADES = [
  'Tesis',
  'Tesina',
  'Residencia Profesional',
  'CENEVAL',
  'Proyecto de Investigación',
] as const;

/** Opciones de modalidad cuando el egresado marca "Curso de titulación" (se guardan igual en `modalidad`). */
export const MODALIDADES_CURSO_TITULACION = [
  'Monografía',
  'Proyecto de Investigación',
  'Tesina',
] as const;

export interface EgresadoForm {
  numero_control: string;
  nombre: string;
  apellido_paterno: string;
  apellido_materno: string;
  carrera: string;
  nivel: string;
  direccion: string;
  telefono: string;
  correo_electronico: string;
  nombre_proyecto: string;
  modalidad: string;
  /** "si" o "no" - se envía al backend según si el checkbox está activado */
  curso_titulacion: string;
  /** Solo al actualizar: true para quitar el archivo actual */
  quitar_archivo?: boolean;
  asesor_interno: string;
  asesor_externo: string;
  director: string;
  asesor_1: string;
  asesor_2: string;
  fecha_registro_anexo: string;
  fecha_expedicion_constancia: string;
  observaciones: string;
}
