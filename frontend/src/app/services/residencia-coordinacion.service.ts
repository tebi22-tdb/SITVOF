import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';

const API = `${environment.apiUrl}/api/residencias-coordinacion`;

export interface ResidenciaItem {
  id: string;
  numero_control: string;
  nombre_alumno: string;
  carrera: string;
  coordinacion: string;
  tipo_proyecto: string;
  nombre_proyecto: string;
  asesor_interno: string;
  asesor_externo?: string;
  fecha_carta_aceptacion: string;
  fecha_inicio: string;
  fecha_fin: string;
  estado: string;
  tiene_anexo_29: boolean;
  nombre_anexo_29?: string;
  tamanio_anexo_29?: number;
  tiene_anexo_30: boolean;
  nombre_anexo_30?: string;
  tamanio_anexo_30?: number;
  fecha_creacion: string;
  fecha_actualizacion: string;
}

export interface ResidenciaStats {
  total: number;
  activas: number;
  pendientes_anexos: number;
  finalizadas: number;
}

export interface ResidenciaForm {
  numero_control: string;
  nombre_alumno: string;
  carrera: string;
  coordinacion: string;
  tipo_proyecto: string;
  nombre_proyecto: string;
  asesor_interno: string;
  asesor_externo?: string;
  fecha_carta_aceptacion: string;
  fecha_inicio: string;
  fecha_fin: string;
  estado: string;
}

export const COORDINACIONES = [
  { id: 'informatica_tics', nombre: 'Informática y TICs' },
  { id: 'biologia', nombre: 'Biología' },
  { id: 'forestal', nombre: 'Forestal' },
  { id: 'agronomia', nombre: 'Agronomía' },
  { id: 'carreras_virtuales', nombre: 'Carreras Virtuales' },
];

export const CARRERAS_POR_COORDINACION: Record<string, string[]> = {
  informatica_tics: [
    'Ingeniería en Sistemas Computacionales',
    'Ingeniería en Tecnologías de la Información y Comunicaciones',
  ],
  biologia: ['Licenciatura en Biología'],
  forestal: ['Ingeniería Forestal'],
  agronomia: [
    'Ingeniería en Agronomía',
    'Ingeniería en Innovación Agrícola Sustentable',
  ],
  carreras_virtuales: ['Ingeniería en Gestión Empresarial'],
};

@Injectable({ providedIn: 'root' })
export class ResidenciaCoordinacionService {
  constructor(private http: HttpClient) {}

  listar(filtros?: {
    coordinacion?: string;
    carrera?: string;
    tipo_proyecto?: string;
    estado?: string;
    busqueda?: string;
  }): Observable<ResidenciaItem[]> {
    let params = new HttpParams();
    if (filtros?.coordinacion?.trim()) params = params.set('coordinacion', filtros.coordinacion);
    if (filtros?.carrera?.trim()) params = params.set('carrera', filtros.carrera);
    if (filtros?.tipo_proyecto?.trim()) params = params.set('tipo_proyecto', filtros.tipo_proyecto);
    if (filtros?.estado?.trim()) params = params.set('estado', filtros.estado);
    if (filtros?.busqueda?.trim()) params = params.set('busqueda', filtros.busqueda);
    return this.http.get<ResidenciaItem[]>(API, { params });
  }

  stats(): Observable<ResidenciaStats> {
    return this.http.get<ResidenciaStats>(`${API}/stats`);
  }

  obtenerPorId(id: string): Observable<ResidenciaItem> {
    return this.http.get<ResidenciaItem>(`${API}/${id}`);
  }

  crear(datos: ResidenciaForm, anexo29?: File | null, anexo30?: File | null): Observable<ResidenciaItem> {
    const form = new FormData();
    form.append('datos', new Blob([JSON.stringify(datos)], { type: 'application/json' }));
    if (anexo29) form.append('anexo_29', anexo29, anexo29.name);
    if (anexo30) form.append('anexo_30', anexo30, anexo30.name);
    return this.http.post<ResidenciaItem>(API, form);
  }

  actualizar(id: string, datos: ResidenciaForm, anexo29?: File | null, anexo30?: File | null): Observable<ResidenciaItem> {
    const form = new FormData();
    form.append('datos', new Blob([JSON.stringify(datos)], { type: 'application/json' }));
    if (anexo29) form.append('anexo_29', anexo29, anexo29.name);
    if (anexo30) form.append('anexo_30', anexo30, anexo30.name);
    return this.http.put<ResidenciaItem>(`${API}/${id}`, form);
  }

  eliminar(id: string): Observable<unknown> {
    return this.http.delete(`${API}/${id}`);
  }

  descargarAnexo(id: string, numero: 29 | 30): Observable<Blob> {
    return this.http.get(`${API}/${id}/anexo-${numero}`, { responseType: 'blob' });
  }
}
