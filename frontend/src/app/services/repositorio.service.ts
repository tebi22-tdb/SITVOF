import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';

export interface TituladoPublico {
  nombre: string;
  carrera: string;
  nivel: string;
  modalidad: string;
  nombre_proyecto: string;
  asesor_interno?: string;
  asesor_externo?: string;
  director?: string;
  asesor_1?: string;
  asesor_2?: string;
  anio?: number;
}

@Injectable({ providedIn: 'root' })
export class RepositorioService {
  private readonly api = `${environment.apiUrl}/api/repositorio`;

  constructor(private http: HttpClient) {}

  listar(): Observable<TituladoPublico[]> {
    return this.http.get<TituladoPublico[]>(this.api);
  }
}
