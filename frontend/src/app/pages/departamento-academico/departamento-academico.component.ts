import { validarArchivoPdf } from '../../core/archivo-pdf';
import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';
import { DomSanitizer, SafeResourceUrl, SafeUrl } from '@angular/platform-browser';
import { Subscription, forkJoin } from 'rxjs';
import { HeaderComponent } from '../../layout/header/header.component';
import { AuthService } from '../../services/auth.service';
import { EgresadoService, DepartamentoListItem, DepartamentoCounts } from '../../services/egresado.service';
import { CatalogoService } from '../../services/catalogo.service';
import { DocenteService, DocenteItem } from '../../services/docente.service';

type TabEstado =
  | 'pendientes'
  | 'en_correccion'
  | 'aprobados'
  | 'sinodales'
  | 'todos'
  | 'anteproyecto'
  | 'liberacion_producto';
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
  counts: DepartamentoCounts = {
    pendientes: 0,
    en_correccion: 0,
    aprobados: 0,
    todos: 0,
    sinodales_por_asignar: 0,
    anteproyecto: 0,
    total_anteproyecto: 0,
    liberacion_producto: 0,
    total_liberacion_producto: 0,
    total_sinodales: 0,
  };
  lista: DepartamentoListItem[] = [];
  cargando = true;
  error = '';
  /** ID del egresado mientras se descarga el Anexo XXXII. */
  descargando32Id: string | null = null;
  /** ID del egresado mientras se descarga el Anexo XXXIII. */
  descargando33Id: string | null = null;
  /** ID del egresado mientras se ejecuta la liberación de residencia. */
  liberandoResidenciaId: string | null = null;
  /** ID del egresado mientras se libera el producto (no residencia). */
  liberandoProductoId: string | null = null;
  /** ID del egresado mientras se confirma recepción Anexo XXXII (evita doble clic). */
  confirmandoXxxiiId: string | null = null;
  /** PDF de tesis seleccionado en pestaña Liberación de producto. */
  archivoTesisLiberacion: File | null = null;
  mensajeLiberacionProducto = '';
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
  sinodalesNumeroOficio = '';
  sinodalesCargando = false;
  sinodalesGuardando = false;
  sinodalesError = '';
  descargandoOficioSinodalesId: string | null = null;
  docentesLista: DocenteItem[] = [];
  vocalDropdownAbierto = false;
  vocalSuplenteDropdownAbierto = false;

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
    private docenteService: DocenteService,
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
  get mostrarBotonVolver(): boolean {
    const rol = this.authService.getUsuario()?.rol?.toLowerCase().trim() ?? '';
    return rol === 'coordinador' || rol === 'administrador';
  }

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
    const yaLiberado = !!this.seleccionado?.fecha_liberacion_producto;
    const doc$ =
      this.tabActivo === 'liberacion_producto' && yaLiberado
        ? this.egresadoService.getTesisLiberacion(id)
        : this.egresadoService.getDocumento(id);
    this.docSub = doc$.subscribe({
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
    if (
      (this.tabActivo === 'todos' || this.tabActivo === 'sinodales') &&
      this.filtroNumeroControl.trim()
    ) {
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
    if (this.tabActivo === 'liberacion_producto') {
      return 'No hay egresados pendientes de liberación de producto (tesis).';
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
      const tabQ = q.get('tab')?.trim().toLowerCase() as TabEstado | undefined;
      const tabsValidas: TabEstado[] = [
        'pendientes',
        'en_correccion',
        'aprobados',
        'sinodales',
        'todos',
        'anteproyecto',
        'liberacion_producto',
      ];
      if (tabQ && tabsValidas.includes(tabQ)) {
        this.tabActivo = tabQ;
      }
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
          liberacion_producto: c.liberacion_producto ?? 0,
          total_liberacion_producto: c.total_liberacion_producto ?? 0,
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
    this.archivoTesisLiberacion = null;
    this.mensajeLiberacionProducto = '';
    this.limpiarSeleccionDocumento();
    this.cargarLista();
  }

  onTesisLiberacionSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0] ?? null;
    this.mensajeLiberacionProducto = '';
    if (!file) {
      this.archivoTesisLiberacion = null;
      return;
    }
    const err = validarArchivoPdf(file);
    if (err) {
      input.value = '';
      this.archivoTesisLiberacion = null;
      this.mensajeLiberacionProducto = err;
      return;
    }
    this.archivoTesisLiberacion = file;
  }

  liberarProductoNoRes(item: DepartamentoListItem, ev?: Event): void {
    ev?.stopPropagation();
    const modalidad = item.modalidad || 'tesis';
    if (!this.archivoTesisLiberacion) {
      this.mensajeLiberacionProducto = `Selecciona el PDF de ${modalidad} antes de liberar.`;
      return;
    }
    this.error = '';
    this.mensajeLiberacionProducto = '';
    this.liberandoProductoId = item.id;
    this.egresadoService.liberarProductoNoResidencia(item.id, this.archivoTesisLiberacion).subscribe({
      next: () => {
        this.liberandoProductoId = null;
        this.archivoTesisLiberacion = null;
        this.mensajeLiberacionProducto = `${modalidad} liberada correctamente.`;
        this.cargarCounts();
        this.cargarLista();
        this.limpiarSeleccionDocumento();
      },
      error: (err: { error?: { error?: string } }) => {
        this.liberandoProductoId = null;
        this.mensajeLiberacionProducto = err?.error?.error ?? `No se pudo liberar ${modalidad}.`;
      },
    });
  }

  volverInicio(): void {
    this.router.navigate(['/home']);
  }

  /** Descarga el Anexo XXXII y marca primera generación en el item local. */
  descargarAnexo32(id: string, ev?: Event): void {
    ev?.stopPropagation();
    if (this.descargando32Id === id) return;
    this.descargando32Id = id;
    this.egresadoService.descargarHoja32(id).subscribe({
      next: ({ blob, fileName }) => {
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url; a.download = fileName; a.click();
        URL.revokeObjectURL(url);
        this.descargando32Id = null;
        const item = this.lista.find(i => i.id === id);
        if (item && !item.fecha_generacion_hoja_32) {
          item.fecha_generacion_hoja_32 = new Date().toISOString();
          if (this.seleccionado?.id === id) this.seleccionado = { ...item };
        }
      },
      error: () => { this.descargando32Id = null; },
    });
  }

  /** Descarga el Anexo XXXIII y marca primera generación en el item local. */
  descargarAnexo33(id: string, ev?: Event): void {
    ev?.stopPropagation();
    if (this.descargando33Id === id) return;
    this.descargando33Id = id;
    this.egresadoService.descargarHoja33(id).subscribe({
      next: ({ blob, fileName }) => {
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url; a.download = fileName; a.click();
        URL.revokeObjectURL(url);
        this.descargando33Id = null;
        const item = this.lista.find(i => i.id === id);
        if (item && !item.fecha_generacion_hoja_33) {
          item.fecha_generacion_hoja_33 = new Date().toISOString();
          if (this.seleccionado?.id === id) this.seleccionado = { ...item };
        }
      },
      error: () => { this.descargando33Id = null; },
    });
  }

  /** Libera la residencia (da paso al siguiente paso en seguimiento). */
  liberarResidencia(id: string, ev?: Event): void {
    ev?.stopPropagation();
    if (this.liberandoResidenciaId === id) return;
    this.liberandoResidenciaId = id;
    this.error = '';
    this.egresadoService.registrarGeneracionAnexos(id).subscribe({
      next: () => {
        this.liberandoResidenciaId = null;
        this.cargarCounts();
        this.cargarLista();
      },
      error: (err: { error?: { error?: string; message?: string }; message?: string }) => {
        const msg = err?.error?.error ?? err?.error?.message ?? err?.message;
        this.error = msg || 'No se pudo liberar.';
        this.liberandoResidenciaId = null;
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

  fechaLiberacionProducto(item: DepartamentoListItem): string {
    const iso = item.fecha_liberacion_producto;
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

  /** True si el valor ya está en la lista de docentes (para no duplicar la opción). */
  docenteEnLista(valor: string): boolean {
    return !valor || this.docentesLista.some((d) => d.nombreCompleto === valor);
  }

  filtrarDocentes(texto: string): DocenteItem[] {
    const q = texto.trim().toLowerCase();
    if (!q) return this.docentesLista;
    return this.docentesLista.filter((d) => d.nombreCompleto.toLowerCase().includes(q));
  }

  seleccionarVocal(nombre: string): void {
    this.sinodalesVocal = nombre;
    this.vocalDropdownAbierto = false;
  }

  seleccionarVocalSuplente(nombre: string): void {
    this.sinodalesVocalSuplente = nombre;
    this.vocalSuplenteDropdownAbierto = false;
  }

  cerrarVocalDropdown(): void {
    setTimeout(() => { this.vocalDropdownAbierto = false; }, 150);
  }

  cerrarVocalSuplenteDropdown(): void {
    setTimeout(() => { this.vocalSuplenteDropdownAbierto = false; }, 150);
  }

  abrirModalSinodales(item: DepartamentoListItem, ev?: Event): void {
    ev?.stopPropagation();
    this.sinodalesModalItem = item;
    this.sinodalesError = '';
    this.sinodalesPresidente = '';
    this.sinodalesSecretario = '';
    this.sinodalesVocal = '';
    this.sinodalesVocalSuplente = '';
    this.sinodalesNumeroOficio = '';
    this.vocalDropdownAbierto = false;
    this.vocalSuplenteDropdownAbierto = false;
    this.sinodalesCargando = true;
    forkJoin({
      docentes: this.docenteService.listar(),
      sinodales: this.egresadoService.getSinodalesAcademico(item.id),
    }).subscribe({
      next: ({ docentes, sinodales }) => {
        this.docentesLista = docentes;
        this.sinodalesPresidente = sinodales.presidente?.trim() ?? '';
        this.sinodalesSecretario = sinodales.secretario?.trim() ?? '';
        this.sinodalesVocal = sinodales.vocal?.trim() ?? '';
        this.sinodalesVocalSuplente = sinodales.vocal_suplente?.trim() ?? '';
        this.sinodalesNumeroOficio = sinodales.numero_oficio?.trim() ?? '';
        this.sinodalesCargando = false;
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
    this.vocalDropdownAbierto = false;
    this.vocalSuplenteDropdownAbierto = false;
  }

  guardarSinodales(): void {
    const item = this.sinodalesModalItem;
    if (!item) return;
    const numeroOficio = this.sinodalesNumeroOficio.trim();
    if (!numeroOficio) {
      this.sinodalesError = 'Indica el número de oficio.';
      return;
    }
    this.sinodalesGuardando = true;
    this.sinodalesError = '';
    this.egresadoService
      .asignarSinodales(item.id, {
        presidente: this.sinodalesPresidente.trim(),
        secretario: this.sinodalesSecretario.trim(),
        vocal: this.sinodalesVocal.trim(),
        vocal_suplente: this.sinodalesVocalSuplente.trim(),
        numero_oficio: numeroOficio,
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

  descargarOficioSinodales(item: DepartamentoListItem, ev?: Event): void {
    ev?.stopPropagation();
    if (!item.sinodales_asignados) return;
    this.descargandoOficioSinodalesId = item.id;
    this.egresadoService.descargarOficioAsignacionSinodales(item.id).subscribe({
      next: (blob) => {
        this.descargandoOficioSinodalesId = null;
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `Oficio-sinodales-${item.numero_control || item.id}.pdf`;
        a.click();
        URL.revokeObjectURL(url);
      },
      error: () => {
        this.descargandoOficioSinodalesId = null;
      },
    });
  }
}
