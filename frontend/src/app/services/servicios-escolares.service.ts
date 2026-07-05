import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';

const API = `${environment.apiUrl}/api/servicios-escolares`;

export interface ServiciosEscolaresBandejaItem {
  id: string;
  numero_control: string;
  nombre_completo: string;
  carrera: string;
  fecha_solicitud_anexo_9_2: string;
}

export interface ServiciosEscolaresDetalle {
  id: string;
  numero_control: string;
  nombre_completo: string;
  fecha_constancia_no_inconveniencia?: string | null;
  fecha_creacion_anexo_9_1?: string | null;
  modalidad: string;
  nombre_proyecto: string;
  fecha_solicitud_anexo_9_2: string;
  fecha_aceptacion_servicios_escolares_anexo_9_2?: string | null;
}

export type TabServiciosEscolares = 'pendientes' | 'atendidos';

@Injectable({ providedIn: 'root' })
export class ServiciosEscolaresService {
  constructor(private http: HttpClient) {}

  listar(estado: TabServiciosEscolares, numeroControl?: string): Observable<ServiciosEscolaresBandejaItem[]> {
    let params = new HttpParams().set('estado', estado);
    const nc = numeroControl?.trim();
    if (nc) params = params.set('numero_control', nc);
    return this.http.get<ServiciosEscolaresBandejaItem[]>(`${API}/constancias-9-2`, { params });
  }

  detalle(id: string): Observable<ServiciosEscolaresDetalle> {
    return this.http.get<ServiciosEscolaresDetalle>(`${API}/constancias-9-2/${id}`);
  }

  confirmar(id: string): Observable<unknown> {
    return this.http.post(`${API}/constancias-9-2/${id}/confirmar`, {});
  }

  revertir(id: string): Observable<unknown> {
    return this.http.post(`${API}/constancias-9-2/${id}/revertir`, {});
  }
}
