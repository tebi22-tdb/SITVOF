// Lo que uso de Angular para hacer las peticiones al backend
import { Injectable } from '@angular/core';
import { HttpClient, HttpParams, HttpResponse } from '@angular/common/http';
import { Observable } from 'rxjs';
import { map } from 'rxjs/operators';
// URL del backend: en producción vacío (mismo servidor); en desarrollo localhost:8081
import { environment } from '../../environments/environment';
import { EgresadoForm } from '../core/datos';

// Base de todas las rutas de egresados en el API
const API = `${environment.apiUrl}/api/egresados`;

// --- Interfaces que devuelve el backend (las defino yo para tipar las respuestas) ---

// Lo que muestra cada cuadrito en la lista del panel izquierdo
export interface EgresadoItem {
  id: string;
  numero_control: string;
  nombre: string;
  carrera: string;
  modalidad?: string;
  fecha_creacion?: string;
  /** Residencia: fecha Anexo XXXI al dar de alta (inicio plazo 6 meses). */
  fecha_registro_anexo_xxxi?: string;
  fecha_envio_solicitud_registro_anteproyecto_depto_academico?: string;
  fecha_confirmacion_recepcion_inicial_anexos_xxxi_xxxii?: string;
  fecha_solicitud_registro_liberacion_depto_academico?: string;
  fecha_enviado_departamento_academico?: string;
  fecha_recibido_registro_liberacion?: string;
  fecha_confirmacion_recibidos_anexo_xxxi_xxxii?: string;
  fecha_actualizacion?: string;
  fecha_creacion_anexo_9_3?: string;
  fecha_confirmacion_entrega_anexo_9_3?: string;
  fecha_solicitud_documentacion_escaneada?: string;
  fecha_envio_documentacion_escaneada_egresado?: string;
  fecha_confirmacion_documentacion_escaneada_recibida?: string;
}

// Archivo adjunto guardado (para mostrar en edición)
export interface DocumentoAdjuntoDetail {
  nombre_original?: string;
  tamanio_bytes?: number;
}

// Lo que se muestra cuando haces clic en un egresado (detalle completo)
export interface EgresadoDetail {
  id: string;
  numero_control: string;
  datos_personales: DatosPersonalesDetail;
  datos_proyecto: DatosProyectoDetail;
  documentos: DocumentosDetail;
  documento_adjunto?: DocumentoAdjuntoDetail;
  estado_general: string;
  fecha_creacion?: string;
  fecha_actualizacion?: string;
  /** ID del proceso activo de titulación. */
  proceso_id?: string;
  /** Cantidad de procesos de titulación (1 = primera vez, 2+ = reintentos). */
  total_procesos?: number;
  /** Resumen de procesos anteriores (vencidos o titulados). */
  procesos_anteriores?: ProcesoAnterior[];
  /** Fecha en que se marcó "Enviado al departamento académico" (paso 1.1). */
  fecha_confirmacion_entrega_egresado_depto?: string;
  fecha_envio_solicitud_registro_anteproyecto_depto_academico?: string;
  tiene_tesis_liberacion?: boolean;
  /** Marca del departamento académico (Anteproyecto registrado); habilita confirmación DEP paso 2 en no residencia. */
  fecha_registrado_departamento?: string;
  segmento_departamento_academico?: string;
  nombre_departamento_academico?: string;
  fecha_confirmacion_recepcion_inicial_anexos_xxxi_xxxii?: string;
  fecha_recepcion_trabajo_division_estudios_prof?: string;
  fecha_solicitud_registro_liberacion_depto_academico?: string;
  fecha_recepcion_registro_liberacion_depto_academico?: string;
  fecha_liberacion_documento_coordinacion_cat?: string;
  fecha_enviado_departamento_academico?: string;
  /** Fecha en que el departamento recibió registro y liberación (paso 2). */
  fecha_recibido_registro_liberacion?: string;
  fecha_confirmacion_recibidos_anexo_xxxi_xxxii?: string;
  fecha_creacion_anexo_9_1?: string;
  fecha_confirmacion_entrega_anexo_9_1?: string;
  /** Cuando división solicita al egresado tramitar la constancia 9.2. */
  fecha_solicitud_anexo_9_2?: string;
  fecha_creacion_anexo_9_2?: string;
  fecha_aceptacion_servicios_escolares_anexo_9_2?: string;
  fecha_confirmacion_recibido_anexo_9_2?: string;
  fecha_solicitud_sinodales?: string;
  fecha_asignacion_sinodales?: string;
  fecha_confirmacion_sinodales_recibidos?: string;
  fecha_agenda_acto_9_3?: string;
  fecha_reagenda_acto_9_3?: string;
  fecha_creacion_anexo_9_3?: string;
  /** Confirmación de entrega del 9.3 a sinodales y sustentante (flujo residencia / DEP). */
  fecha_confirmacion_entrega_anexo_9_3?: string;
  fecha_solicitud_documentacion_escaneada?: string;
  fecha_envio_documentacion_escaneada_egresado?: string;
  fecha_confirmacion_documentacion_escaneada_recibida?: string;
  fecha_solicitud_reenvio_documentacion_escaneada?: string;
  observaciones_reenvio_documentacion_escaneada?: string;
}

// Datos personales dentro del detalle
export interface DatosPersonalesDetail {
  nombre: string;
  apellido_paterno: string;
  apellido_materno: string;
  carrera: string;
  nivel: string;
  direccion?: string;
  telefono?: string;
  correo_electronico?: string;
}

// Datos del proyecto dentro del detalle
export interface DatosProyectoDetail {
  nombre_proyecto: string;
  modalidad: string;
  /** "si" o "no" - viene de la base de datos */
  curso_titulacion?: string;
  asesor_interno?: string;
  asesor_externo?: string;
  director?: string;
  asesor_1?: string;
  asesor_2?: string;
}

// Documentos (anexo y constancia) dentro del detalle
export interface DocumentosDetail {
  anexo_xxxi?: { fecha_registro?: string; estado?: string };
  constancia_no_inconveniencia?: { fecha_expedicion?: string; estado?: string };
}

export interface ProcesoAnterior {
  proceso_id: string;
  modalidad: string;
  nombre_proyecto: string;
  estado: string;
  fecha_creacion: string;
  fecha_cierre?: string;
  fecha_enviado_departamento_academico?: string;
  fecha_recibido_registro_liberacion?: string;
  fecha_confirmacion_recibidos_anexo_xxxi_xxxii?: string;
  fecha_liberacion_documento_coordinacion_cat?: string;
  fecha_confirmacion_entrega_egresado_depto?: string;
  fecha_envio_solicitud_registro_anteproyecto_depto_academico?: string;
  tiene_tesis_liberacion?: boolean;
  fecha_recepcion_trabajo_division_estudios_prof?: string;
  fecha_solicitud_registro_liberacion_depto_academico?: string;
  fecha_recepcion_registro_liberacion_depto_academico?: string;
  fecha_confirmacion_recepcion_inicial_anexos_xxxi_xxxii?: string;
  fecha_creacion_anexo_9_1?: string;
  fecha_confirmacion_entrega_anexo_9_1?: string;
  fecha_solicitud_anexo_9_2?: string;
  fecha_aceptacion_servicios_escolares_anexo_9_2?: string;
  fecha_confirmacion_recibido_anexo_9_2?: string;
  fecha_solicitud_sinodales?: string;
  fecha_confirmacion_sinodales_recibidos?: string;
  fecha_agenda_acto_9_3?: string;
  fecha_creacion_anexo_9_3?: string;
  fecha_confirmacion_entrega_anexo_9_3?: string;
  fecha_solicitud_documentacion_escaneada?: string;
  fecha_envio_documentacion_escaneada_egresado?: string;
  fecha_confirmacion_documentacion_escaneada_recibida?: string;
  fecha_titulacion?: string;
}

/** Item de la lista para departamento académico (Pendientes, En corrección, Aprobados, Todos). */
export interface DepartamentoListItem {
  id: string;
  nombre?: string;
  numero_control: string;
  carrera?: string;
  modalidad: string;
  fecha_actualizacion?: string;
  fecha_enviado_departamento_academico?: string;
  /** "pendiente" | "con_observaciones" | "aprobado" para el badge. */
  estado_revision?: string;
  sinodales_asignados?: boolean;
  fecha_solicitud_sinodales?: string;
  /** Pestaña Anteproyecto: fecha en que DEP envió anteproyecto + Anexo XXXI al departamento. */
  fecha_envio_anteproyecto_depto?: string;
  fecha_registrado_departamento?: string;
  /** Pestaña Liberación de producto: ya liberado. */
  fecha_liberacion_producto?: string;
}

/** Revisión guardada (del backend). */
export interface RevisionApi {
  id: string;
  egresado_id: string;
  numero_revision: number;
  fecha: string;
  revisado_por: string;
  resultado: string;
  observaciones?: string;
  enviado_al_egresado?: boolean;
  fecha_envio_egresado?: string;
  tiene_documento_adjunto?: boolean;
  documento_nombre?: string;
  documento_content_type?: string;
  documento_tamanio_bytes?: number;
}

/** Conteos para las pestañas del departamento académico. */
export interface DepartamentoCounts {
  pendientes: number;
  en_correccion: number;
  aprobados: number;
  todos: number;
  sinodales_por_asignar?: number;
  anteproyecto?: number;
  /** Total de registros en la sección (pendientes + ya registrados). */
  total_anteproyecto?: number;
  liberacion_producto?: number;
  total_liberacion_producto?: number;
  total_sinodales?: number;
}

export interface AgendaActo93OcupadosResponse {
  ocupados: string[];
}

/** Respuesta del POST al crear egresado (incluye aviso sobre correo de credenciales). */
export interface EgresadoCrearResponse {
  id: string;
  numero_control: string;
  credenciales_enviadas_correo?: boolean | null;
  aviso_credenciales?: string | null;
}

// Servicio que usa HttpClient para hablar con el backend
@Injectable({ providedIn: 'root' })
export class EgresadoService {
  constructor(private http: HttpClient) {}

  // Pido la lista de egresados; filtros opcionales: numero_control, fecha_desde, fecha_hasta, tipo_filtro (anexo_xxxi | constancia)
  // aplicar_scope_departamento=false: evita recortes de modalidad en vistas globales de seguimiento.
  listar(filtros?: {
    numero_control?: string;
    fecha_desde?: string;
    fecha_hasta?: string;
    tipo_filtro?: string;
    aplicar_scope_departamento?: boolean;
  }): Observable<EgresadoItem[]> {
    let params = new HttpParams();
    if (filtros?.numero_control?.trim()) {
      params = params.set('numero_control', filtros.numero_control.trim());
    }
    if (filtros?.fecha_desde) {
      params = params.set('fecha_desde', filtros.fecha_desde);
    }
    if (filtros?.fecha_hasta) {
      params = params.set('fecha_hasta', filtros.fecha_hasta);
    }
    if (filtros?.tipo_filtro) {
      params = params.set('tipo_filtro', filtros.tipo_filtro);
    }
    if (typeof filtros?.aplicar_scope_departamento === 'boolean') {
      params = params.set('aplicar_scope_departamento', String(filtros.aplicar_scope_departamento));
    }
    return this.http.get<EgresadoItem[]>(API, { params });
  }

  // Pido un egresado por su id (el que viene de MongoDB)
  obtenerPorId(id: string, aplicarScopeDepartamento?: boolean, cacheBust?: boolean): Observable<EgresadoDetail> {
    let params = new HttpParams();
    if (typeof aplicarScopeDepartamento === 'boolean') {
      params = params.set('aplicar_scope_departamento', String(aplicarScopeDepartamento));
    }
    if (cacheBust) {
      params = params.set('_cb', String(Date.now()));
    }
    return this.http.get<EgresadoDetail>(`${API}/${id}`, { params });
  }

  // Por si el id falla, busco por número de control (respaldo)
  obtenerPorNumeroControl(numeroControl: string): Observable<EgresadoDetail> {
    return this.http.get<EgresadoDetail>(`${API}/por-numero/${encodeURIComponent(numeroControl)}`);
  }

  /** Seguimiento del egresado: obtiene su propio registro (requiere sesión de egresado). */
  getMiSeguimiento(cacheBust?: boolean): Observable<EgresadoDetail> {
    let params = new HttpParams();
    if (cacheBust) {
      params = params.set('_cb', String(Date.now()));
    }
    return this.http.get<EgresadoDetail>(`${API}/mi-seguimiento`, { params });
  }

  // Envío los datos del formulario y el archivo en FormData (multipart) al POST del backend
  crear(datos: EgresadoForm, archivo: File | null): Observable<EgresadoCrearResponse> {
    const formData = new FormData();
    formData.append('datos', new Blob([JSON.stringify(datos)], { type: 'application/json' }));
    if (archivo) {
      formData.append('archivo', archivo, archivo.name);
    }
    return this.http.post<EgresadoCrearResponse>(API, formData);
  }

  eliminar(id: string): Observable<unknown> {
    return this.http.post(`${API}/eliminar/${id}`, {});
  }

  activarNuevoProceso(id: string, datos: EgresadoForm, archivo: File | null): Observable<unknown> {
    const form = new FormData();
    form.append('datos', new Blob([JSON.stringify(datos)], { type: 'application/json' }));
    if (archivo) form.append('archivo', archivo, archivo.name);
    return this.http.post(`${API}/${id}/nuevo-proceso`, form);
  }

  enviarDepartamentoAcademico(id: string): Observable<unknown> {
    return this.http.post(`${API}/${id}/enviar-departamento-academico`, {});
  }

  marcarRegistradoDepartamento(id: string): Observable<unknown> {
    return this.http.post(`${API}/${id}/departamento/marcar-registrado`, {});
  }

  solicitarRegistroAnteproyectoNoResidencia(id: string): Observable<unknown> {
    return this.http.post(`${API}/${id}/no-residencia/solicitar-registro-anteproyecto`, {});
  }

  confirmarRecepcionInicialAnexosXxxiXxxiiNoResidencia(id: string): Observable<unknown> {
    return this.http.post(`${API}/${id}/no-residencia/confirmar-recepcion-inicial-anexos-xxxi-xxxii`, {});
  }

  confirmarRecepcionTrabajoNoResidencia(id: string): Observable<unknown> {
    return this.http.post(`${API}/${id}/no-residencia/confirmar-recepcion-trabajo-division`, {});
  }

  confirmarEntregaEgresadoDeptoNoResidencia(id: string): Observable<unknown> {
    return this.http.post(`${API}/${id}/no-residencia/confirmar-entrega-egresado-depto`, {});
  }

  liberarProductoNoResidencia(id: string, archivo: File): Observable<unknown> {
    const formData = new FormData();
    formData.append('archivo', archivo, archivo.name);
    return this.http.post(`${API}/${id}/no-residencia/liberar-producto`, formData);
  }

  getTesisLiberacion(id: string): Observable<{ blob: Blob; contentType: string; fileName: string }> {
    return this.http
      .get(`${API}/${id}/tesis-liberacion`, { responseType: 'blob', observe: 'response' })
      .pipe(
        map((res) => {
          const ct = res.headers.get('Content-Type') || 'application/pdf';
          const disp = res.headers.get('Content-Disposition') || '';
          const match = disp.match(/filename[*]?=(?:UTF-8'')?"?([^";\n]+)"?/i);
          const fileName = match ? match[1].trim() : 'tesis-liberacion.pdf';
          return { blob: res.body!, contentType: ct, fileName };
        }),
      );
  }

  confirmarRecepcionRegistroLiberacionNoResidencia(id: string): Observable<unknown> {
    return this.http.post(`${API}/${id}/no-residencia/confirmar-recepcion-registro-liberacion-depto`, {});
  }

  /** Conteos para pestañas (solo rol academico). */
  getDepartamentoCounts(segmento?: string | null): Observable<DepartamentoCounts> {
    let params = new HttpParams();
    if (segmento?.trim()) params = params.set('segmento', segmento.trim());
    return this.http.get<DepartamentoCounts>(`${API}/departamento/counts`, { params });
  }

  /** Lista para departamento académico por estado: pendientes, en_correccion, aprobados, todos (solo rol academico). */
  listarDepartamento(estado: string, segmento?: string | null): Observable<DepartamentoListItem[]> {
    let params = new HttpParams().set('estado', estado);
    if (segmento?.trim()) params = params.set('segmento', segmento.trim());
    return this.http.get<DepartamentoListItem[]>(`${API}/departamento`, { params });
  }

  /** Lista revisiones del egresado (solo rol academico). */
  listarRevisiones(egresadoId: string): Observable<RevisionApi[]> {
    return this.http.get<RevisionApi[]>(`${API}/${egresadoId}/revisiones`);
  }

  /** Crea una revisión (Enviar revisión con observaciones). Solo rol academico. */
  crearRevision(
    egresadoId: string,
    body: { resultado: string; observaciones?: string },
    archivo?: File | null,
  ): Observable<RevisionApi> {
    if (!archivo) {
      return this.http.post<RevisionApi>(`${API}/${egresadoId}/revisiones`, body);
    }
    const formData = new FormData();
    formData.append('resultado', body.resultado);
    if (body.observaciones?.trim()) formData.append('observaciones', body.observaciones.trim());
    formData.append('archivo', archivo, archivo.name);
    return this.http.post<RevisionApi>(`${API}/${egresadoId}/revisiones`, formData);
  }

  /** Marca una revisión como enviada al egresado para mostrarla en su seguimiento. */
  enviarRevisionAEgresado(egresadoId: string, revisionId: string): Observable<RevisionApi> {
    return this.http.post<RevisionApi>(`${API}/${egresadoId}/revisiones/${revisionId}/enviar`, {});
  }

  /** Seguimiento del egresado: revisiones enviadas desde apoyo/departamento. */
  getMisRevisionesEnviadas(): Observable<RevisionApi[]> {
    return this.http.get<RevisionApi[]>(`${API}/mi-seguimiento/revisiones`);
  }

  /** Egresado: sustituye el PDF/Word del expediente que revisa Coordinación de Apoyo a la Titulación (con observaciones pendientes). */
  reemplazarDocumentoExpedienteMiSeguimiento(archivo: File): Observable<void> {
    const form = new FormData();
    form.append('archivo', archivo, archivo.name);
    return this.http.post<void>(`${API}/mi-seguimiento/documento-expediente/reemplazar`, form);
  }

  descargarDocumentoRevision(egresadoId: string, revisionId: string): Observable<{ blob: Blob; fileName: string }> {
    return this.http
      .get(`${API}/${egresadoId}/revisiones/${revisionId}/documento`, { responseType: 'blob', observe: 'response' })
      .pipe(
        map((res) => {
          const disp = res.headers.get('Content-Disposition') || '';
          const match = disp.match(/filename[*]?=(?:UTF-8'')?"?([^";\n]+)"?/i);
          const fileName = match ? match[1].trim() : `revision-${revisionId}.pdf`;
          return { blob: res.body!, fileName };
        }),
      );
  }

  descargarDocumentoMiRevision(revisionId: string): Observable<{ blob: Blob; fileName: string }> {
    return this.http
      .get(`${API}/mi-seguimiento/revisiones/${revisionId}/documento`, { responseType: 'blob', observe: 'response' })
      .pipe(
        map((res) => {
          const disp = res.headers.get('Content-Disposition') || '';
          const match = disp.match(/filename[*]?=(?:UTF-8'')?"?([^";\n]+)"?/i);
          const fileName = match ? match[1].trim() : `revision-${revisionId}.pdf`;
          return { blob: res.body!, fileName };
        }),
      );
  }

  /** Obtiene el documento adjunto del egresado (PDF/Word) para visualización. Solo rol academico. */
  getDocumento(egresadoId: string): Observable<{ blob: Blob; contentType: string; fileName: string }> {
    return this.http
      .get(`${API}/${egresadoId}/documento`, { responseType: 'blob', observe: 'response' })
      .pipe(
        map((res) => {
          const ct = res.headers.get('Content-Type') || 'application/octet-stream';
          const disp = res.headers.get('Content-Disposition') || '';
          const match = disp.match(/filename[*]?=(?:UTF-8'')?"?([^";\n]+)"?/i);
          const fileName = match ? match[1].trim() : 'documento';
          return { blob: res.body!, contentType: ct, fileName };
        }),
      );
  }

  liberar(id: string): Observable<unknown> {
    return this.http.post(`${API}/${id}/liberar`, {});
  }

  confirmarRecibidosAnexosXxxiXxxii(id: string): Observable<unknown> {
    return this.http.post(`${API}/${id}/confirmar-recibidos-anexo-xxxi-xxxii`, {});
  }

  descargarAnexo91(id: string): Observable<Blob> {
    return this.http.get(`${API}/${id}/anexo-9-1`, { responseType: 'blob' });
  }

  getSinodalesAcademico(
    egresadoId: string,
  ): Observable<{ presidente?: string; secretario?: string; vocal?: string; vocal_suplente?: string }> {
    return this.http.get<{ presidente?: string; secretario?: string; vocal?: string; vocal_suplente?: string }>(
      `${API}/${egresadoId}/sinodales`,
    );
  }

  asignarSinodales(
    egresadoId: string,
    body: { presidente: string; secretario: string; vocal: string; vocal_suplente: string },
  ): Observable<unknown> {
    return this.http.post(`${API}/${egresadoId}/sinodales`, body);
  }

  reemplazarDocumentoAdjunto(id: string, archivo: File): Observable<unknown> {
    const formData = new FormData();
    formData.append('archivo', archivo, archivo.name);
    return this.http.post(`${API}/${id}/documento/reemplazar`, formData);
  }

  confirmarEntregaAnexo91(id: string): Observable<unknown> {
    return this.http.post(`${API}/${id}/confirmar-entrega-anexo-9-1`, {});
  }

  solicitarConstancia92Division(id: string): Observable<unknown> {
    return this.http.post(`${API}/${id}/solicitar-constancia-9-2-division`, {});
  }

  descargarAnexo92(id: string): Observable<Blob> {
    return this.http.get(`${API}/${id}/anexo-9-2`, { responseType: 'blob' });
  }

  confirmarRecibidoAnexo92(id: string): Observable<unknown> {
    return this.http.post(`${API}/${id}/confirmar-recibido-anexo-9-2`, {});
  }

  solicitarSinodales(id: string): Observable<unknown> {
    return this.http.post(`${API}/${id}/solicitar-sinodales`, {});
  }

  confirmarSinodalesRecibidos(id: string): Observable<unknown> {
    return this.http.post(`${API}/${id}/confirmar-sinodales-recibidos`, {});
  }

  agendarActo93(id: string, fechaHora: string): Observable<unknown> {
    return this.http.post(`${API}/${id}/agendar-acto-9-3`, { fecha_hora: fechaHora });
  }

  getAgendaActo93Ocupados(): Observable<AgendaActo93OcupadosResponse> {
    return this.http.get<AgendaActo93OcupadosResponse>(`${API}/agenda-acto-9-3/ocupados`);
  }

  descargarAnexo93(id: string): Observable<HttpResponse<Blob>> {
    return this.http.get(`${API}/${id}/anexo-9-3`, { responseType: 'blob', observe: 'response' });
  }

  confirmarEntregaAnexo93(id: string): Observable<unknown> {
    return this.http.post(`${API}/${id}/confirmar-entrega-anexo-9-3`, {});
  }

  subirDocumentoFinal(archivo: File): Observable<unknown> {
    const formData = new FormData();
    formData.append('archivo', archivo);
    return this.http.post(`${API}/mi-seguimiento/documento-final`, formData);
  }

  /** Comprueba originalidad del título contra registros existentes. */
  verificarOriginalidad(
    titulo: string,
    excluirId?: string,
    modalidad?: string,
  ): Observable<{ estado: string; titulo_similar: string; expediente_estado?: string; motivo?: string }> {
    let params = new HttpParams().set('titulo', titulo);
    if (excluirId) params = params.set('excluirId', excluirId);
    if (modalidad?.trim()) params = params.set('modalidad', modalidad.trim());
    return this.http.get<{ estado: string; titulo_similar: string; expediente_estado?: string; motivo?: string }>(
      `${API}/verificar-originalidad`,
      { params },
    );
  }

  /** Comprueba si el número de control ya está en uso (alta / edición). */
  verificarNumeroControlAlta(
    numeroControl: string,
    modalidad?: string,
    excluirId?: string,
  ): Observable<{ estado: string; expediente_estado?: string; egresado_id?: string }> {
    let params = new HttpParams().set('numero_control', numeroControl.trim());
    if (modalidad?.trim()) params = params.set('modalidad', modalidad.trim());
    if (excluirId) params = params.set('excluirId', excluirId);
    return this.http.get<{ estado: string; expediente_estado?: string; egresado_id?: string }>(`${API}/verificar-numero-control`, {
      params,
    });
  }

  solicitarDocumentacionEscaneada(id: string): Observable<unknown> {
    return this.http.post(`${API}/${id}/solicitar-documentacion-escaneada`, {});
  }

  confirmarDocumentacionEscaneadaRecibida(id: string): Observable<unknown> {
    return this.http.post(`${API}/${id}/confirmar-documentacion-escaneada-recibida`, {});
  }

  getDocumentacionEscaneada(egresadoId: string): Observable<{ blob: Blob; contentType: string; fileName: string }> {
    return this.http
      .get(`${API}/${egresadoId}/documentacion-escaneada`, { responseType: 'blob', observe: 'response' })
      .pipe(
        map((res) => {
          const ct = res.headers.get('Content-Type') || 'application/pdf';
          const disp = res.headers.get('Content-Disposition') || '';
          const match = disp.match(/filename[*]?=(?:UTF-8'')?"?([^";\n]+)"?/i);
          const fileName = match ? match[1].trim() : 'documentacion-escaneada.pdf';
          return { blob: res.body!, contentType: ct, fileName };
        }),
      );
  }

  solicitarDocumentacionEscaneadaNuevamente(id: string, observaciones: string): Observable<unknown> {
    return this.http.post(`${API}/${id}/solicitar-documentacion-escaneada-nuevamente`, { observaciones });
  }

  /** Egresado: sube uno o más PDF (multipart, campo `archivos`). */
  subirDocumentacionEscaneadaMiSeguimiento(archivos: File[]): Observable<unknown> {
    const formData = new FormData();
    for (const f of archivos) {
      formData.append('archivos', f, f.name);
    }
    return this.http.post(`${API}/mi-seguimiento/documentacion-escaneada`, formData);
  }

  actualizar(id: string, datos: EgresadoForm, archivo: File | null): Observable<unknown> {
    const formData = new FormData();
    formData.append('datos', new Blob([JSON.stringify(datos)], { type: 'application/json' }));
    if (archivo) {
      formData.append('archivo', archivo, archivo.name);
    }
    return this.http.post(`${API}/${id}`, formData);
  }
}
