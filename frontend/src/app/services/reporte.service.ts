import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';

const API = `${environment.apiUrl}/api/reportes`;

export interface ReporteFiltros {
  fecha_desde?: string;
  fecha_hasta?: string;
  modalidad?: string;
  carrera?: string;
  generacion?: string;
}

export interface ReporteConteoItem {
  etiqueta: string;
  cantidad: number;
  porcentaje: number;
}

export interface ReporteTiempoEtapaItem {
  etapa: string;
  etiqueta: string;
  dias_promedio: number | null;
  muestra: number;
}

export interface ReporteTasaAprobacion {
  total_primeras_revisiones: number;
  aprobadas_en_primera_revision: number;
  porcentaje: number;
}

/**
 * De los egresados del filtro (uno por persona, su proceso activo), cuántos
 * ya llegaron a "titulado". Un egresado que venció y se tituló después con
 * otra modalidad cuenta una sola vez — ver `ReporteService.calcularTasaTitulacion`.
 */
export interface ReporteTasaTitulacion {
  total_procesos: number;
  titulados: number;
  porcentaje: number;
}

/** Un punto de la tendencia mensual — cuántos procesos se registraron ese mes. */
export interface ReporteTendenciaMensualItem {
  mes: string;
  etiqueta: string;
  cantidad: number;
}

/** Un hito del embudo de avance — cuántos procesos ya alcanzaron ese hito, contado de forma independiente. */
export interface ReporteEmbudoEtapaItem {
  clave: string;
  etiqueta: string;
  cantidad: number;
}

export interface ReporteResumenCoordinador {
  filtros: ReporteFiltros;
  total_procesos: number;
  /** claves: "registrado" | "titulado" | "vencido" — solo incluye las que tienen al menos 1 caso. */
  conteo_por_estado_general: Partial<Record<string, number>>;
  /** claves: "en_tiempo" | "rezagado" | "vencido" | "concluido" — solo incluye las que tienen al menos 1 caso. */
  conteo_por_estado_plazo: Partial<Record<string, number>>;
  distribucion_modalidad: ReporteConteoItem[];
  distribucion_carrera: ReporteConteoItem[];
  tiempos_promedio_etapa_dias: ReporteTiempoEtapaItem[];
  tasa_aprobacion_primera_revision: ReporteTasaAprobacion;
  tasa_titulacion: ReporteTasaTitulacion;
  tendencia_mensual: ReporteTendenciaMensualItem[];
  embudo_etapas: ReporteEmbudoEtapaItem[];
}

/**
 * Filtros propios del listado, además de los ya compartidos en `ReporteFiltros`.
 * `departamento_slug` usa el mismo slug que ya expone `catalogoService.departamentos$`
 * (no el id real de `Departamento` — el backend lo resuelve, ver `ReporteService.listadoEgresados`).
 */
export interface ListadoEgresadosFiltros {
  generacion_ingreso?: number;
  departamento_slug?: string;
  estado_general?: string;
}

/**
 * Una fila del listado de egresados — el directorio consultable (nombre,
 * número de control, carrera, generación de ingreso, estado) que reemplazó
 * los resúmenes agregados "por departamento"/"servicios escolares".
 * `generacion_ingreso` es null cuando el número de control no matchea el
 * patrón esperado (ver `NumeroControl`, backend) — el egresado sigue
 * apareciendo, solo no puede filtrarse/encontrarse por generación.
 */
export interface ReporteEgresadoListado {
  id: string;
  nombre_completo: string;
  numero_control: string;
  carrera: string;
  departamento_nombre: string | null;
  generacion_ingreso: number | null;
  modalidad: string;
  estado_general: string;
  estado_plazo: string;
  fecha_titulacion: string | null;
}

@Injectable({ providedIn: 'root' })
export class ReporteService {
  constructor(private http: HttpClient) {}

  resumenCoordinador(filtros: ReporteFiltros): Observable<ReporteResumenCoordinador> {
    return this.http.get<ReporteResumenCoordinador>(`${API}/coordinador/resumen`, { params: this.aParams(filtros) });
  }

  listadoEgresados(filtros: ReporteFiltros, extra: ListadoEgresadosFiltros): Observable<ReporteEgresadoListado[]> {
    return this.http.get<ReporteEgresadoListado[]>(`${API}/coordinador/listado-egresados`, {
      params: this.aParams({ ...filtros, ...extra }),
    });
  }

  /** Libro de Excel con el resumen general (KPIs) — mismos filtros que `resumenCoordinador`. */
  exportarExcel(filtros: ReporteFiltros): Observable<Blob> {
    return this.http.get(`${API}/coordinador/exportar.xlsx`, { params: this.aParams(filtros), responseType: 'blob' });
  }

  /** Libro de Excel del listado — documento propio, no una hoja del resumen general. */
  exportarListadoExcel(filtros: ReporteFiltros, extra: ListadoEgresadosFiltros): Observable<Blob> {
    return this.http.get(`${API}/coordinador/listado-egresados/exportar.xlsx`, {
      params: this.aParams({ ...filtros, ...extra }),
      responseType: 'blob',
    });
  }

  private aParams(filtros: object): HttpParams {
    let params = new HttpParams();
    Object.entries(filtros).forEach(([key, valor]) => {
      if ((typeof valor === 'string' && valor) || typeof valor === 'number') {
        params = params.set(key, valor);
      }
    });
    return params;
  }
}
