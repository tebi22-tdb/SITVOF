import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';

const API = `${environment.apiUrl}/api/config-institucional`;

export interface ConfigGlobalResponse {
  jefeDivisionNombre: string;
  jefeDivisionTitulo: string;
  tieneImagenAnual: boolean;
}

export interface ConfigDepartamentoResponse {
  slug: string;
  /** Nombre del catálogo — solo lectura, no se envía en el PUT. */
  nombre?: string;
  departamentoNombreCompleto: string;
  jefeNombre: string;
  jefeCargo: string;
  jefeIniciales: string;
}

@Injectable({ providedIn: 'root' })
export class ConfigInstitucionalService {
  constructor(private http: HttpClient) {}

  getGlobal(): Observable<ConfigGlobalResponse> {
    return this.http.get<ConfigGlobalResponse>(`${API}/global`);
  }

  putGlobal(data: { jefeDivisionNombre: string; jefeDivisionTitulo: string }): Observable<ConfigGlobalResponse> {
    return this.http.put<ConfigGlobalResponse>(`${API}/global`, data);
  }

  subirImagenAnual(file: File): Observable<ConfigGlobalResponse> {
    const form = new FormData();
    form.append('imagen', file);
    return this.http.post<ConfigGlobalResponse>(`${API}/imagen-anual`, form);
  }

  getDepartamentos(): Observable<ConfigDepartamentoResponse[]> {
    return this.http.get<ConfigDepartamentoResponse[]>(`${API}/departamentos`);
  }

  putDepartamento(slug: string, data: Omit<ConfigDepartamentoResponse, 'slug'>): Observable<ConfigDepartamentoResponse> {
    return this.http.put<ConfigDepartamentoResponse>(`${API}/departamento/${slug}`, data);
  }
}
