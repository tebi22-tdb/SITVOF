import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';
import { DomSanitizer, SafeResourceUrl, SafeUrl } from '@angular/platform-browser';
import { Subscription } from 'rxjs';
import { HeaderComponent } from '../../layout/header/header.component';
import { AuthService } from '../../services/auth.service';
import { EgresadoService, DepartamentoListItem, DepartamentoCounts } from '../../services/egresado.service';
import { CatalogoService } from '../../services/catalogo.service';

type TabEstado = 'pendientes' | 'en_correccion' | 'aprobados' | 'sinodales' | 'todos' | 'anteproyecto';
@Component({
  selector: 'app-departamento-academico',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterModule, HeaderComponent],
  templateUrl: './departamento-academico.component.html',
  styleUrl: './departamento-academico.component.css',
})
export class DepartamentoAcademicoComponent implements OnInit, OnDestroy {
  tabActivo: TabEstado = 'pendientes';
  tituloDepartamento = 'Coordinacion de apoyo a la titulacion';
  esModoRevision = false;
  /** Filtro por slug de departamento (solo coordinación; query `?segmento=`). */
  segmentoCoordinacion: string | null = null;
  counts: DepartamentoCounts = { pendientes: 0, en_correccion: 0, aprobados: 0, todos: 0, sinodales_por_asignar: 0, anteproyecto: 0, total_anteproyecto: 0, total_sinodales: 0 };
  lista: DepartamentoListItem[] = [];
  cargando = true;
  error = '';
  /** ID del egresado mientras se ejecuta Liberar (evita doble clic). */
  liberandoId: string | null = null;
  /** ID del egresado mientras se confirma recepción Anexo XXXII (evita doble clic). */
  confirmandoXxxiiId: string | null = null;
  /** Número de control para buscar (pestañas Todos/Sinodales). */
  searchNumeroControl = '';
  /** Filtro aplicado al hacer clic en Buscar. */
  filtroNumeroControl = '';

  /** Modal asignar sinodales */
  sinodalesModalItem: DepartamentoListItem | null = null;
  sinodalesPresidente = '';
  sinodalesSecretario = '';
  sinodalesVocal = '';
  sinodalesVocalSuplente = '';
  sinodalesCargando = false;
  sinodalesGuardando = false;
  sinodalesError = '';

  /** Filas simuladas mientras carga la tabla. */
  readonly skeletonPlaceholders = [0, 1, 2, 3, 4, 5];

  /** Fila seleccionada (vista dividida: documento a la derecha). Solo pestañas distintas a Sinodales. */
  seleccionado: DepartamentoListItem | null = null;
  cargandoDocumento = false;
  errorDocumento = '';
  documentoUrl: string | null = null;
  documentoUrlSeguro: SafeResourceUrl | null = null;
  documentoHrefSeguro: SafeUrl | null = null;
  documentoContentType = '';
  documentoFileName = '';
  private docSub: Subscription | null = null;
  private querySub: Subscription | null = null;

  constructor(
    private egresadoService: EgresadoService,
    private authService: AuthService,
    private router: Router,
    private route: ActivatedRoute,
    private sanitizer: DomSanitizer,
    private catalogoService: CatalogoService,
  ) {}

  ngOnDestroy(): void {
    this.querySub?.unsubscribe();
    this.revocarDocumentoUrl();
    this.docSub?.unsubscribe();
  }

  get esDocumentoPdf(): boolean {
    return (this.documentoContentType || '').toLowerCase().includes('pdf');
  }

  /**
   * Pestañas "En corrección / Aprobados" solo en vista global de coordinación.
   * Con `?segmento=` la bandeja es la del departamento (Pendientes, Sinodales, Todos), como usuario académico.
   */
  get mostrarTabsRevisionCoordinacion(): boolean {
    return this.esModoRevision && !this.segmentoCoordinacion;
  }

  /**
   * Vista dividida (tabla + documento):
   * - No aplica en Sinodales.
   * - En Coordinación/Administrador, en pestaña "En corrección" se oculta panel de documento.
   */
  get usarSplitConDocumento(): boolean {
    if (this.tabActivo === 'sinodales') return false;
    if (this.authService.isCoordinador() && this.tabActivo === 'en_correccion') return false;
    return true;
  }

  /** Selecciona egresado y carga el PDF/documento en el panel derecho. */
  seleccionarFila(item: DepartamentoListItem): void {
    this.seleccionado = item;
    this.cargarDocumentoSeleccionado();
  }

  private revocarDocumentoUrl(): void {
    if (this.documentoUrl) {
      URL.revokeObjectURL(this.documentoUrl);
      this.documentoUrl = null;
    }
    this.documentoUrlSeguro = null;
    this.documentoHrefSeguro = null;
  }

  private limpiarSeleccionDocumento(): void {
    this.docSub?.unsubscribe();
    this.docSub = null;
    this.seleccionado = null;
    this.revocarDocumentoUrl();
    this.cargandoDocumento = false;
    this.errorDocumento = '';
    this.documentoContentType = '';
    this.documentoFileName = '';
  }

  private cargarDocumentoSeleccionado(): void {
    const id = this.seleccionado?.id;
    if (!id) return;
    this.docSub?.unsubscribe();
    this.revocarDocumentoUrl();
    this.cargandoDocumento = true;
    this.errorDocumento = '';
    this.documentoContentType = '';
    this.documentoFileName = '';
    this.docSub = this.egresadoService.getDocumento(id).subscribe({
      next: ({ blob, contentType, fileName }) => {
        this.cargandoDocumento = false;
        this.documentoContentType = contentType || '';
        this.documentoFileName = fileName || 'documento';
        const url = URL.createObjectURL(blob);
        this.documentoUrl = url;
        this.documentoUrlSeguro = this.sanitizer.bypassSecurityTrustResourceUrl(url);
        this.documentoHrefSeguro = this.sanitizer.bypassSecurityTrustUrl(url);
      },
      error: (err: { error?: { error?: string }; message?: string; statusText?: string }) => {
        this.cargandoDocumento = false;
        const msg = err?.error?.error ?? err?.message ?? err?.statusText;
        this.errorDocumento = msg ? `No se pudo cargar el documento: ${msg}` : 'No se pudo cargar el documento.';
      },
    });
  }

  esResidencia(modalidad: string): boolean {
    return this.catalogoService.esResidencia(modalidad);
  }

  /** Marca el anteproyecto como registrado en la bandeja del departamento. No repercute en el flujo de seguimiento. */
  marcarRegistrado(item: DepartamentoListItem, ev?: Event): void {
    ev?.stopPropagation();
    if (this.confirmandoXxxiiId) return;
    this.confirmandoXxxiiId = item.id;
    this.error = '';
    this.egresadoService.marcarRegistradoDepartamento(item.id).subscribe({
      next: () => {
        this.confirmandoXxxiiId = null;
        this.cargarCounts();
        this.cargarLista();
      },
      error: (err: { error?: { error?: string }; message?: string }) => {
        this.confirmandoXxxiiId = null;
        const msg = err?.error?.error ?? err?.message;
        this.error = msg ? `No se pudo marcar: ${msg}` : 'No se pudo marcar como registrado.';
      },
    });
  }

  /** Abre Revisión de documento (solo para modalidades que no son residencia). */
  irARevision(item: DepartamentoListItem): void {
    if (this.catalogoService.esResidencia(item.modalidad)) return;
    if (this.authService.isAcademico()) {
      this.router.navigate(['/departamento-academico/revision', item.id]);
    } else {
      this.router.navigate(['/home/revisiones/revision', item.id]);
    }
  }

  /** Lista a mostrar: en "Todos" y "Sinodales" filtrada por número de control si hay búsqueda. */
  get listaVisible(): DepartamentoListItem[] {
    const tabConBusqueda = this.tabActivo === 'todos' || this.tabActivo === 'sinodales';
    if (!tabConBusqueda || !this.filtroNumeroControl.trim()) {
      return this.lista;
    }
    const term = this.filtroNumeroControl.trim().toLowerCase();
    return this.lista.filter((item) => item.numero_control.toLowerCase().includes(term));
  }

  get mensajeListaVacia(): string {
    if ((this.tabActivo === 'todos' || this.tabActivo === 'sinodales') && this.filtroNumeroControl.trim()) {
      return 'No se encontró ningún registro con ese número de control.';
    }
    if (this.mostrarTabsRevisionCoordinacion && this.tabActivo === 'en_correccion') {
      return 'No hay expedientes en corrección.';
    }
    if (this.tabActivo === 'sinodales') {
      return 'No hay registros de sinodales para mostrar.';
    }
    if (this.tabActivo === 'anteproyecto') {
      return 'No hay anteproyectos enviados al departamento pendientes de respuesta.';
    }
    return 'No hay registros en esta sección.';
  }

  aplicarBusqueda(): void {
    this.filtroNumeroControl = this.searchNumeroControl.trim();
  }

  ngOnInit(): void {
    const usuario = this.authService.getUsuario();
    const segSlug = usuario?.segmento_academico?.trim() ?? '';
    this.esModoRevision = !segSlug && !(usuario?.carreras_asignadas?.length ?? 0);
    this.tituloDepartamento = this.esModoRevision
      ? 'Coordinacion de apoyo a la titulacion'
      : (this.catalogoService.nombreDepartamentoPorSlug(segSlug) ?? 'Coordinacion de apoyo a la titulacion');

    this.querySub = this.route.queryParamMap.subscribe((q) => {
      const s = q.get('segmento')?.trim() || null;
      this.segmentoCoordinacion = this.authService.isAcademico() ? null : s;
      if (this.segmentoCoordinacion && (this.tabActivo === 'en_correccion' || this.tabActivo === 'aprobados')) {
        this.tabActivo = 'pendientes';
      }
      if (this.esModoRevision) {
        if (this.segmentoCoordinacion) {
          this.tituloDepartamento = this.catalogoService.nombreDepartamentoPorSlug(this.segmentoCoordinacion) ?? 'Coordinación de apoyo a la titulación';
        } else {
          this.tituloDepartamento = 'Coordinación de apoyo a la titulación';
        }
      }
      this.cargarCounts();
      this.cargarLista();
    });
  }

  cargarCounts(): void {
    this.egresadoService.getDepartamentoCounts(this.segmentoCoordinacion).subscribe({
      next: (c) => {
        this.counts = {
          pendientes: c.pendientes ?? 0,
          en_correccion: c.en_correccion ?? 0,
          aprobados: c.aprobados ?? 0,
          todos: c.todos ?? 0,
          sinodales_por_asignar: c.sinodales_por_asignar ?? 0,
          anteproyecto: c.anteproyecto ?? 0,
          total_anteproyecto: c.total_anteproyecto ?? 0,
          total_sinodales: c.total_sinodales ?? 0,
        };
      },
      error: () => {},
    });
  }

  cargarLista(): void {
    this.error = '';
    this.cargando = true;
    this.egresadoService.listarDepartamento(this.tabActivo, this.segmentoCoordinacion).subscribe({
      next: (items) => {
        this.lista = items;
        this.cargando = false;
        if (this.seleccionado) {
          const u = items.find((x) => x.id === this.seleccionado!.id);
          if (u) this.seleccionado = u;
          else this.limpiarSeleccionDocumento();
        }
      },
      error: (err: HttpErrorResponse) => {
        const st = err.status;
        const detalle =
          (typeof err.error === 'object' && err.error && 'error' in err.error
            ? String((err.error as { error?: string }).error)
            : '') || err.message;
        if (st === 403) {
          this.error =
            'No tienes permiso para ver esta lista. Si acabas de actualizar el sistema, cierra sesión, vuelve a entrar y asegúrate de que el backend esté en la versión nueva.';
        } else if (st === 0 || st === 504) {
          this.error =
            'No hay conexión con el backend (¿corre Spring Boot en el puerto 8081 con `npm start` y el proxy?).';
        } else {
          this.error =
            st > 0
              ? `No se pudo cargar la lista (${st}).${detalle ? ` ${detalle}` : ''}`
              : 'No se pudo cargar la lista.';
        }
        this.cargando = false;
      },
    });
  }

  cambiarTab(tab: TabEstado): void {
    if (this.mostrarTabsRevisionCoordinacion && tab === 'sinodales') return;
    this.tabActivo = tab;
    this.filtroNumeroControl = '';
    this.limpiarSeleccionDocumento();
    this.cargarLista();
  }

  volverInicio(): void {
    this.router.navigate(['/home']);
  }

  /** Liberar (marca recibido registro y liberación). Solo Residencia Profesional; el registro pasa a Aprobados. */
  liberar(id: string): void {
    this.error = '';
    this.liberandoId = id;
    this.egresadoService.liberar(id).subscribe({
      next: () => {
        this.liberandoId = null;
        this.cargarCounts();
        this.cargarLista();
      },
      error: (err: { error?: { error?: string; message?: string }; message?: string }) => {
        this.liberandoId = null;
        const msg = err?.error?.error ?? err?.error?.message ?? err?.message;
        this.error = msg ? `No se pudo liberar: ${msg}` : 'No se pudo liberar. Solo aplica a Residencia Profesional.';
      },
    });
  }

  /** Nombre a mostrar: viene del backend; si falta o está vacío, usa número de control. */
  nombreDisplay(item: DepartamentoListItem): string {
    const n = item.nombre?.trim();
    if (n) return n;
    return `Solicitud ${item.numero_control}`;
  }

  /** Fecha en que se recibió (enviado al departamento académico): DD/MM/YYYY. */
  fechaRecepcion(item: DepartamentoListItem): string {
    const iso = item.fecha_enviado_departamento_academico;
    if (!iso) return '—';
    const d = new Date(iso);
    if (isNaN(d.getTime())) return iso;
    const dia = d.getDate().toString().padStart(2, '0');
    const mes = (d.getMonth() + 1).toString().padStart(2, '0');
    const anio = d.getFullYear();
    return `${dia}/${mes}/${anio}`;
  }

  /** Fecha de última modificación: DD/MM/YYYY. */
  ultimoCambio(item: DepartamentoListItem): string {
    const iso = item.fecha_actualizacion;
    if (!iso) return '—';
    const d = new Date(iso);
    if (isNaN(d.getTime())) return iso;
    const dia = d.getDate().toString().padStart(2, '0');
    const mes = (d.getMonth() + 1).toString().padStart(2, '0');
    const anio = d.getFullYear();
    return `${dia}/${mes}/${anio}`;
  }

  /** Fecha en que DEP envió anteproyecto + Anexo XXXI al departamento: DD/MM/YYYY. */
  fechaEnvioAnteproyecto(item: DepartamentoListItem): string {
    const iso = item.fecha_envio_anteproyecto_depto;
    if (!iso) return '—';
    const d = new Date(iso);
    if (isNaN(d.getTime())) return iso;
    const dia = d.getDate().toString().padStart(2, '0');
    const mes = (d.getMonth() + 1).toString().padStart(2, '0');
    const anio = d.getFullYear();
    return `${dia}/${mes}/${anio}`;
  }

  /** Fecha solicitud sinodales (ISO instant): DD/MM/YYYY HH:mm. */
  fechaSolicitudSinodales(item: DepartamentoListItem): string {
    const iso = item.fecha_solicitud_sinodales;
    if (!iso) return '—';
    const d = new Date(iso);
    if (isNaN(d.getTime())) return iso;
    const dia = d.getDate().toString().padStart(2, '0');
    const mes = (d.getMonth() + 1).toString().padStart(2, '0');
    const anio = d.getFullYear();
    const h = d.getHours().toString().padStart(2, '0');
    const m = d.getMinutes().toString().padStart(2, '0');
    return `${dia}/${mes}/${anio} ${h}:${m}`;
  }

  abrirModalSinodales(item: DepartamentoListItem, ev?: Event): void {
    ev?.stopPropagation();
    this.sinodalesModalItem = item;
    this.sinodalesError = '';
    this.sinodalesPresidente = '';
    this.sinodalesSecretario = '';
    this.sinodalesVocal = '';
    this.sinodalesVocalSuplente = '';
    this.sinodalesCargando = true;
    this.egresadoService.getSinodalesAcademico(item.id).subscribe({
      next: (r: { presidente?: string; secretario?: string; vocal?: string; vocal_suplente?: string }) => {
        this.sinodalesCargando = false;
        this.sinodalesPresidente = r.presidente?.trim() ?? '';
        this.sinodalesSecretario = r.secretario?.trim() ?? '';
        this.sinodalesVocal = r.vocal?.trim() ?? '';
        this.sinodalesVocalSuplente = r.vocal_suplente?.trim() ?? '';
      },
      error: () => {
        this.sinodalesCargando = false;
        this.sinodalesError = 'No se pudieron cargar los datos. Intenta de nuevo.';
      },
    });
  }

  cerrarModalSinodales(): void {
    this.sinodalesModalItem = null;
    this.sinodalesError = '';
    this.sinodalesCargando = false;
    this.sinodalesGuardando = false;
  }

  guardarSinodales(): void {
    const item = this.sinodalesModalItem;
    if (!item) return;
    this.sinodalesGuardando = true;
    this.sinodalesError = '';
    this.egresadoService
      .asignarSinodales(item.id, {
        presidente: this.sinodalesPresidente.trim(),
        secretario: this.sinodalesSecretario.trim(),
        vocal: this.sinodalesVocal.trim(),
        vocal_suplente: this.sinodalesVocalSuplente.trim(),
      })
      .subscribe({
        next: () => {
          this.sinodalesGuardando = false;
          this.cerrarModalSinodales();
          this.cargarCounts();
          this.cargarLista();
        },
        error: (err: { error?: { error?: string }; message?: string }) => {
          this.sinodalesGuardando = false;
          const msg = err?.error?.error ?? err?.message;
          this.sinodalesError = msg ? String(msg) : 'No se pudo guardar.';
        },
      });
  }
}
