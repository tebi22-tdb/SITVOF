import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';

const API = `${environment.apiUrl}/api/docentes`;

export interface DocenteItem {
  id: string;
  nombreCompleto: string;
  correo: string;
  cedula: string;
  genero?: string | null;
  activo: boolean;
}

export interface DocenteRequest {
  nombreCompleto: string;
  correo: string;
  cedula: string;
  genero: string;
}

@Injectable({ providedIn: 'root' })
export class DocenteService {
  constructor(private http: HttpClient) {}

  listar(): Observable<DocenteItem[]> {
    return this.http.get<DocenteItem[]>(API);
  }

  crear(req: DocenteRequest): Observable<DocenteItem> {
    return this.http.post<DocenteItem>(API, req);
  }

  actualizar(id: string, req: DocenteRequest): Observable<DocenteItem> {
    return this.http.put<DocenteItem>(`${API}/${id}`, req);
  }

  eliminar(id: string): Observable<unknown> {
    return this.http.delete(`${API}/${id}`);
  }
}
