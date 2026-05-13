/** Opción del formulario «Agregar usuario» (etiqueta institucional → rol + segmento en backend). */
export interface PerfilCreacionUsuarioItem {
  id: string;
  etiqueta: string;
}

/** Perfiles estáticos (no dependen del catálogo de departamentos). */
export const PERFILES_ESTATICOS: PerfilCreacionUsuarioItem[] = [
  { id: 'apoyo_titulacion',          etiqueta: 'División de Estudios Profesionales — Apoyo a Titulación' },
  { id: 'division_estudios_prof_admin', etiqueta: 'División de Estudios Profesionales — Administrativo' },
  { id: 'academico_general',         etiqueta: 'Coordinacion de apoyo a la titulacion' },
  { id: 'servicios_escolares',       etiqueta: 'Departamento de Servicios Escolares' },
];

/**
 * Genera perfiles de académico a partir de la lista de departamentos del catálogo.
 * El id de cada perfil es `academico_{slug}` para que `datosRolDesdePerfil` lo resuelva.
 */
export function perfilesDesdeDepartamentos(
  departamentos: { nombre: string; slug: string | null }[],
): PerfilCreacionUsuarioItem[] {
  return departamentos
    .filter(d => d.slug)
    .map(d => ({ id: `academico_${d.slug}`, etiqueta: d.nombre }));
}

export interface DatosRolDesdePerfil {
  rol: string;
  segmento_academico: string;
  carreras_asignadas: string[];
}

/**
 * Convierte la opción del desplegable en lo que guarda Mongo y el API de usuarios.
 * Para perfiles de departamento (academico_{slug}), extrae el slug directamente del id.
 * Las carreras se sobreescriben en home.component.ts con `catalogoService.carrerasPorSlugSync`.
 */
export function datosRolDesdePerfil(perfil: string): DatosRolDesdePerfil {
  switch (perfil) {
    case 'apoyo_titulacion':
      return { rol: 'apoyo_titulacion', segmento_academico: '', carreras_asignadas: [] };
    case 'division_estudios_prof_admin':
      return { rol: 'division_estudios_prof_admin', segmento_academico: '', carreras_asignadas: [] };
    case 'servicios_escolares':
      return { rol: 'servicios_escolares', segmento_academico: '', carreras_asignadas: [] };
    case 'academico_general':
      return { rol: 'academico', segmento_academico: '', carreras_asignadas: [] };
    default:
      if (perfil.startsWith('academico_')) {
        const slug = perfil.slice('academico_'.length);
        return { rol: 'academico', segmento_academico: slug, carreras_asignadas: [] };
      }
      return { rol: 'division_estudios_prof_admin', segmento_academico: '', carreras_asignadas: [] };
  }
}
