import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';

const API = `${environment.apiUrl}/api/config-institucional`;

export interface ConfigGlobalResponse {
  jefeDivisionNombre: string;
  jefeDivisionTitulo: string;
  jefeDivisionIniciales: string;
  jefeDivisionCorreo: string;
}

export interface ConfigDepartamentoResponse {
  slug: string;
  /** Nombre del catálogo — solo lectura, no se envía en el PUT. */
  nombre?: string;
  departamentoNombreCompleto: string;
  jefeNombre: string;
  jefeCargo: string;
  jefeIniciales: string;
  correo: string;
}

export interface ConfigServiciosEscolaresResponse {
  jefeNombre: string;
  jefeCargo: string;
}

export interface CodigosAnexos {
  codigoAnexo91: string;
  revisionAnexo91: string;
  codigoAnexo92: string;
  revisionAnexo92: string;
  codigoAnexo93: string;
  revisionAnexo93: string;
}

export interface ImagenAnexoSlot {
  clave: string;
  etiqueta: string;
  tieneImagenPersonalizada: boolean;
}

export interface InsigniaAnexo {
  id: string;
  dataUri: string;
}

export interface AvisoCorreoModulo {
  modulo: string;
  segmento?: string | null;
}

export interface EstadoCorreoResponse {
  ok: boolean;
  ultimo_error?: string | null;
  momento?: string | null;
  avisos?: AvisoCorreoModulo[];
}

@Injectable({ providedIn: 'root' })
export class ConfigInstitucionalService {
  constructor(private http: HttpClient) {}

  getGlobal(): Observable<ConfigGlobalResponse> {
    return this.http.get<ConfigGlobalResponse>(`${API}/global`);
  }

  getEstadoCorreo(): Observable<EstadoCorreoResponse> {
    return this.http.get<EstadoCorreoResponse>(`${API}/estado-correo`);
  }

  putGlobal(data: {
    jefeDivisionNombre: string;
    jefeDivisionTitulo: string;
    jefeDivisionIniciales: string;
    jefeDivisionCorreo: string;
  }): Observable<ConfigGlobalResponse> {
    return this.http.put<ConfigGlobalResponse>(`${API}/global`, data);
  }

  getDepartamentos(): Observable<ConfigDepartamentoResponse[]> {
    return this.http.get<ConfigDepartamentoResponse[]>(`${API}/departamentos`);
  }

  putDepartamento(slug: string, data: Omit<ConfigDepartamentoResponse, 'slug'>): Observable<ConfigDepartamentoResponse> {
    return this.http.put<ConfigDepartamentoResponse>(`${API}/departamento/${slug}`, data);
  }

  getCodigosAnexos(): Observable<CodigosAnexos> {
    return this.http.get<CodigosAnexos>(`${API}/codigos-anexos`);
  }

  putCodigosAnexos(data: CodigosAnexos): Observable<CodigosAnexos> {
    return this.http.put<CodigosAnexos>(`${API}/codigos-anexos`, data);
  }

  getServiciosEscolares(): Observable<ConfigServiciosEscolaresResponse> {
    return this.http.get<ConfigServiciosEscolaresResponse>(`${API}/servicios-escolares`);
  }

  putServiciosEscolares(data: ConfigServiciosEscolaresResponse): Observable<ConfigServiciosEscolaresResponse> {
    return this.http.put<ConfigServiciosEscolaresResponse>(`${API}/servicios-escolares`, data);
  }

  getImagenesAnexos(): Observable<ImagenAnexoSlot[]> {
    return this.http.get<ImagenAnexoSlot[]>(`${API}/imagenes-anexos`);
  }

  subirImagenAnexo(clave: string, file: File): Observable<ImagenAnexoSlot[]> {
    const form = new FormData();
    form.append('imagen', file);
    return this.http.post<ImagenAnexoSlot[]>(`${API}/imagenes-anexos/${clave}`, form);
  }

  getInsigniasAnexos(): Observable<InsigniaAnexo[]> {
    return this.http.get<InsigniaAnexo[]>(`${API}/insignias-anexos`);
  }

  agregarInsigniaAnexo(file: File): Observable<InsigniaAnexo[]> {
    const form = new FormData();
    form.append('imagen', file);
    return this.http.post<InsigniaAnexo[]>(`${API}/insignias-anexos`, form);
  }

  eliminarInsigniaAnexo(id: string): Observable<InsigniaAnexo[]> {
    return this.http.delete<InsigniaAnexo[]>(`${API}/insignias-anexos/${id}`);
  }

  reordenarInsigniasAnexos(idsEnOrden: string[]): Observable<InsigniaAnexo[]> {
    return this.http.put<InsigniaAnexo[]>(`${API}/insignias-anexos/orden`, { idsEnOrden });
  }

  vistaTextosAnexo(anexo: string): Observable<string> {
    return this.http.get(`${API}/textos-anexos/${anexo}`, { responseType: 'text' });
  }

  guardarTextosAnexo(anexo: string, bloques: Record<string, string>): Observable<{ bloques: Record<string, string> }> {
    return this.http.put<{ bloques: Record<string, string> }>(`${API}/textos-anexos/${anexo}`, { bloques });
  }
}
