export type SegmentoAcademico =
  | 'virtuales'
  | 'ingenierias'
  | 'economico_administrativo'
  | 'ciencias_basicas';

export interface SegmentoAcademicoDef {
  id: SegmentoAcademico;
  nombre: string;
  carreras: string[];
}

/** Textos de carrera iguales a `CARRERAS` en `datos.ts`. */
export const SEGMENTOS_ACADEMICOS: SegmentoAcademicoDef[] = [
  {
    id: 'virtuales',
    nombre: 'Carreras virtuales',
    carreras: ['INGENIERIA SISTEMAS COMPUTACIONALES', 'INGENIERIA EN GESTIÓN EMPRESARIAL (VIRTUAL)'],
  },
  {
    id: 'ingenierias',
    nombre: 'Ingenierías',
    carreras: ['INGENIERIA EN AGRONOMÍA', 'INGENIERIA FORESTAL', 'INGENIERIA AMBIENTAL'],
  },
  {
    id: 'economico_administrativo',
    nombre: 'Departamento Económico-Administrativo',
    carreras: [
      'INGENIERIA INFORMÁTICA',
      'INGENIERIA EN TECNOLOGIA DE LA INFORMACION Y COMUNICACION ',
      'INGENIERIA EN CIENCIA DE DATOS',
    ],
  },
  {
    id: 'ciencias_basicas',
    nombre: 'Departamento de ciencias básicas',
    carreras: ['LICENCIATURA EN BIOLOGÍA'],
  },
];

export function obtenerSegmentoAcademicoDef(id: string | null | undefined): SegmentoAcademicoDef | null {
  if (!id) return null;
  return SEGMENTOS_ACADEMICOS.find((s) => s.id === id) ?? null;
}
