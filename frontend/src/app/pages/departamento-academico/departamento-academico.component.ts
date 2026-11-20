import { HttpErrorResponse } from '@angular/common/http';
import { validarArchivoPdf } from '../../core/archivo-pdf';
import { mensajeErrorApiConBlob } from '../../core/http-blob-error';
import { textoErrorUsuario } from '../../core/mensaje-conexion';
import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterModule } from '@angular/router';
import { DomSanitizer, SafeResourceUrl, SafeUrl } from '@angular/platform-browser';
import { Subscription, forkJoin } from 'rxjs';
import { HeaderComponent } from '../../layout/header/header.component';
import { PdfViewerPanelComponent } from '../../shared/pdf-viewer-panel/pdf-viewer-panel.component';
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

/** Secciones con badge de pendientes que pueden “recibir” un expediente nuevo. */
type SeccionAlerta =
  | 'pendientes'
  | 'en_correccion'
  | 'anteproyecto'
  | 'liberacion_producto'
  | 'sinodales';

@Component({
  selector: 'app-departamento-academico',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterModule, HeaderComponent, PdfViewerPanelComponent],
  templateUrl: './departamento-academico.component.html',
  styleUrl: './departamento-academico.component.css',
})
export class DepartamentoAcademicoComponent implements OnInit, OnDestroy {
  tabActivo: string = 'pendientes';
  pestanasPropias: { nombre: string; total: number }[] = [];
  tituloDepartamento = 'Departamento académico';
  esModoRevision = false;
  /** Filtro por slug de departamento (solo coordinación; query `?segmento=`). */
  segmentoCoordinacion: string | null = null;
  counts: DepartamentoCounts = {
    pendientes: 0,
    en_correccion: 0,
    aprobados: 0,
    total_residencia: 0,
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

  /** Pestañas cuyo badge debe parpadear (llegó algo a esa sección). */
  tabsConNovedad = new Set<SeccionAlerta>();
  /** Filas nuevas en la lista de la pestaña actual. */
  idsFilaNueva = new Set<string>();

  private snapshotCountsPendientes: Partial<Record<SeccionAlerta, number>> = {};
  private baselineCountsListo = false;
  private idsVistosPorTab = new Map<string, Set<string>>();
  private flashTabTimers = new Map<SeccionAlerta, ReturnType<typeof setTimeout>>();
  private flashFilaTimers = new Map<string, ReturnType<typeof setTimeout>>();
  private pollTimer: ReturnType<typeof setInterval> | null = null;
  private ultimoSegmentoPoll: string | null | undefined = undefined;
  private static readonly DURACION_FLASH_MS = 3600;
  private static readonly POLL_MS = 30_000;
  /** ID del egresado mientras se descarga el Anexo XXXII. */
  descargando32Id: string | null = null;
  /** ID del egresado mientras se descarga el Anexo XXXIII. */
  descargando33Id: string | null = null;
  /** ID del egresado mientras se ejecuta la liberación de residencia. */
  liberandoResidenciaId: string | null = null;
  /** ID del egresado mientras se libera el producto (no residencia). */
  liberandoProductoId: string | null = null;
  /** ID del egresado mientras se revierte un paso (solo coordinación/admin). */
  revirtiendoId: string | null = null;
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
  xxxiiModalItem: DepartamentoListItem | null = null;
  xxxiiObservaciones = '';
  xxxiiError = '';
  xxxiiiModalItem: DepartamentoListItem | null = null;
  xxxiiiAsesor = '';
  xxxiiiRevisor1 = '';
  xxxiiiRevisor2 = '';
  xxxiiiCargando = false;
  xxxiiiError = '';
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
  documentoViewerKey = 0;
  private docSub: Subscription | null = null;
  private querySub: Subscription | null = null;
  private catalogoSub: Subscription | null = null;

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
    this.catalogoSub?.unsubscribe();
    this.querySub?.unsubscribe();
    this.revocarDocumentoUrl();
    this.docSub?.unsubscribe();
    if (this.pollTimer) clearInterval(this.pollTimer);
    this.flashTabTimers.forEach((t) => clearTimeout(t));
    this.flashFilaTimers.forEach((t) => clearTimeout(t));
    this.flashTabTimers.clear();
    this.flashFilaTimers.clear();
  }

  seccionConNovedad(seccion: SeccionAlerta): boolean {
    return this.tabsConNovedad.has(seccion);
  }

  esItemNuevo(id: string): boolean {
    return this.idsFilaNueva.has(id);
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
    return rol === 'administrador';
  }

  /** Botón Revertir: solo administrador, no usuarios académicos de departamento. */
  get puedeRevertirPasoDepartamento(): boolean {
    return this.authService.puedeRevertirPasoDepartamento();
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
    return this.tabActivo !== 'sinodales';
  }

  /** Selecciona egresado y carga el PDF/documento en el panel derecho. */
  seleccionarFila(item: DepartamentoListItem): void {
    this.quitarFlashFila(item.id);
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
        if (!blob?.size) {
          this.errorDocumento = 'Este expediente no tiene documento adjunto.';
          return;
        }
        this.documentoContentType = contentType || '';
        this.documentoFileName = fileName || 'documento';
        const url = URL.createObjectURL(blob);
        this.documentoUrl = url;
        this.documentoViewerKey++;
        this.documentoUrlSeguro = this.sanitizer.bypassSecurityTrustResourceUrl(url);
        this.documentoHrefSeguro = this.sanitizer.bypassSecurityTrustUrl(url);
      },
      error: (err) => {
        this.cargandoDocumento = false;
        if (err instanceof HttpErrorResponse && err.status === 404) {
          this.errorDocumento = 'Este expediente no tiene documento adjunto.';
          return;
        }
        void mensajeErrorApiConBlob(err, 'No se pudo cargar el documento.').then((msg) => {
          this.errorDocumento = msg;
        });
      },
    });
  }

  esResidencia(modalidad: string): boolean {
    return this.catalogoService.esResidencia(modalidad);
  }

  /** Tesis, tesina, proyecto de investigación y monografía firman con director. */
  esModalidadDirector(modalidad: string): boolean {
    const m = (modalidad || '')
      .normalize('NFD')
      .replace(/[\u0300-\u036f]/g, '')
      .toLowerCase();
    return m.includes('tesis') || m.includes('tesina') || m.includes('monograf') || m.includes('investigacion');
  }

  etiquetaMentorXxxiii(modalidad: string): string {
    if (this.esResidencia(modalidad)) return 'Asesor interno';
    if (this.esModalidadDirector(modalidad)) return 'Director';
    return 'Asesor';
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
        this.cargarBandeja();
      },
      error: (err: { error?: { error?: string }; message?: string }) => {
        this.confirmandoXxxiiId = null;
        this.error = textoErrorUsuario(err, 'No se pudo marcar como registrado.');
      },
    });
  }

  /** Abre Revisión de documento. En la pestaña Residencia del departamento no se revisa. */
  irARevision(item: DepartamentoListItem): void {
    if (!this.muestraRevisionExpediente(item)) return;
    if (this.authService.isAcademico()) {
      this.router.navigate(['/departamento-academico/revision', item.id]);
    } else {
      this.router.navigate(['/home/revisiones/revision', item.id]);
    }
  }

  /**
   * Tesis, tesina y el resto sí se revisan en Coordinación (Pendientes, En corrección, Aprobados).
   * La pestaña Residencia del departamento solo libera; no abre observaciones ni aprobar.
   */
  muestraRevisionExpediente(item: DepartamentoListItem): boolean {
    if (this.esResidencia(item.modalidad) || item.estado_revision === 'aprobado') return false;
    if (
      this.tabActivo === 'todos' ||
      this.tabActivo === 'anteproyecto' ||
      this.tabActivo === 'liberacion_producto' ||
      this.tabActivo === 'sinodales'
    ) {
      return false;
    }
    if (!this.mostrarTabsRevisionCoordinacion && this.tabActivo === 'pendientes') return false;
    return true;
  }

  /** Lista a mostrar: en "Todos" y "Sinodales" filtrada por número de control si hay búsqueda. El movimiento más reciente va arriba. */
  get listaVisible(): DepartamentoListItem[] {
    const tabConBusqueda = this.tabActivo === 'todos' || this.tabActivo === 'sinodales';
    const base =
      !tabConBusqueda || !this.filtroNumeroControl.trim()
        ? this.lista
        : this.lista.filter((item) =>
            item.numero_control.toLowerCase().includes(this.filtroNumeroControl.trim().toLowerCase()),
          );
    return [...base].sort((a, b) => this.cmpMovimientoReciente(a, b));
  }

  /** Más reciente primero. Sin fecha, al final. */
  private cmpMovimientoReciente(a: DepartamentoListItem, b: DepartamentoListItem): number {
    const ta = this.msUltimoMovimiento(a);
    const tb = this.msUltimoMovimiento(b);
    if (ta !== tb) return tb - ta;
    return (a.nombre || a.numero_control).localeCompare(b.nombre || b.numero_control, 'es');
  }

  private msUltimoMovimiento(item: DepartamentoListItem): number {
    const fechas = [
      item.fecha_actualizacion,
      item.fecha_enviado_departamento_academico,
      item.fecha_envio_anteproyecto_depto,
      item.fecha_registrado_departamento,
      item.fecha_liberacion_producto,
      item.fecha_solicitud_sinodales,
      item.fecha_generacion_anexos_xxxii_xxxiii,
      item.fecha_generacion_hoja_32,
      item.fecha_generacion_hoja_33,
    ];
    let max = 0;
    for (const iso of fechas) {
      if (!iso) continue;
      const t = Date.parse(iso);
      if (!Number.isNaN(t) && t > max) max = t;
    }
    return max;
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
    this.actualizarTitulo();
    this.catalogoSub = this.catalogoService.departamentos$.subscribe(() => this.actualizarTitulo());

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
      this.actualizarTitulo();
      if (this.ultimoSegmentoPoll !== this.segmentoCoordinacion) {
        this.ultimoSegmentoPoll = this.segmentoCoordinacion;
        this.reiniciarDeteccionNovedades();
      }
      this.cargarBandeja();
      if (!this.pollTimer) {
        this.pollTimer = setInterval(
          () => this.cargarBandeja(true),
          DepartamentoAcademicoComponent.POLL_MS,
        );
      }
    });
  }

  /** Coordinación solo si el usuario no pertenece a un departamento. El resto muestra su departamento. */
  private actualizarTitulo(): void {
    if (this.esModoRevision) {
      const slug = this.segmentoCoordinacion;
      this.tituloDepartamento = slug
        ? (this.catalogoService.nombreDepartamentoPorSlug(slug) ?? 'Coordinación de apoyo a la titulación')
        : 'Coordinación de apoyo a la titulación';
      return;
    }
    const usuario = this.authService.getUsuario();
    const slug = usuario?.segmento_academico?.trim() ?? '';
    const porSlug = slug ? this.catalogoService.nombreDepartamentoPorSlug(slug) : null;
    const porCarreras = this.catalogoService.inferirNombreDepartamentoPorCarreras(
      usuario?.carreras_asignadas ?? [],
    );
    this.tituloDepartamento = porSlug ?? porCarreras ?? 'Departamento académico';
  }

  private reiniciarDeteccionNovedades(): void {
    this.baselineCountsListo = false;
    this.snapshotCountsPendientes = {};
    this.idsVistosPorTab.clear();
    this.tabsConNovedad = new Set();
    this.idsFilaNueva = new Set();
    this.flashTabTimers.forEach((t) => clearTimeout(t));
    this.flashFilaTimers.forEach((t) => clearTimeout(t));
    this.flashTabTimers.clear();
    this.flashFilaTimers.clear();
  }

  /** Carga conteos y lista en una sola petición (evita doble findAll en backend). */
  cargarBandeja(silencioso = false): void {
    if (!silencioso) {
      this.error = '';
      this.cargando = true;
    }
    this.egresadoService.getDepartamentoBandeja(this.tabActivo, this.segmentoCoordinacion).subscribe({
      next: ({ counts, items, pestanas }) => {
        this.pestanasPropias = pestanas ?? [];
        const nextCounts: DepartamentoCounts = {
          pendientes: counts.pendientes ?? 0,
          en_correccion: counts.en_correccion ?? 0,
          aprobados: counts.aprobados ?? 0,
          total_residencia: counts.total_residencia ?? 0,
          todos: counts.todos ?? 0,
          sinodales_por_asignar: counts.sinodales_por_asignar ?? 0,
          anteproyecto: counts.anteproyecto ?? 0,
          total_anteproyecto: counts.total_anteproyecto ?? 0,
          liberacion_producto: counts.liberacion_producto ?? 0,
          total_liberacion_producto: counts.total_liberacion_producto ?? 0,
          total_sinodales: counts.total_sinodales ?? 0,
        };
        this.detectarNovedadesPorSeccion(nextCounts);
        this.counts = nextCounts;
        this.detectarFilasNuevas(items);
        this.lista = items;
        if (!silencioso) this.cargando = false;
        if (this.seleccionado) {
          const u = items.find((x) => x.id === this.seleccionado!.id);
          if (u) this.seleccionado = u;
          else this.limpiarSeleccionDocumento();
        }
      },
      error: (err: HttpErrorResponse) => {
        if (silencioso) return;
        this.error =
          err.status === 403
            ? 'No tienes permiso para ver esta lista. Cierra sesión y vuelve a entrar.'
            : textoErrorUsuario(err, 'No se pudo cargar la lista.');
        this.cargando = false;
      },
    });
  }

  cargarCounts(): void {
    this.egresadoService.getDepartamentoCounts(this.segmentoCoordinacion).subscribe({
      next: (c) => {
        const nextCounts: DepartamentoCounts = {
          pendientes: c.pendientes ?? 0,
          en_correccion: c.en_correccion ?? 0,
          aprobados: c.aprobados ?? 0,
          total_residencia: c.total_residencia ?? 0,
          todos: c.todos ?? 0,
          sinodales_por_asignar: c.sinodales_por_asignar ?? 0,
          anteproyecto: c.anteproyecto ?? 0,
          total_anteproyecto: c.total_anteproyecto ?? 0,
          liberacion_producto: c.liberacion_producto ?? 0,
          total_liberacion_producto: c.total_liberacion_producto ?? 0,
          total_sinodales: c.total_sinodales ?? 0,
        };
        this.detectarNovedadesPorSeccion(nextCounts);
        this.counts = nextCounts;
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
        this.error =
          err.status === 403
            ? 'No tienes permiso para ver esta lista. Cierra sesión y vuelve a entrar.'
            : textoErrorUsuario(err, 'No se pudo cargar la lista.');
        this.cargando = false;
      },
    });
  }

  cambiarTab(tab: string): void {
    if (this.mostrarTabsRevisionCoordinacion && tab === 'sinodales') return;
    this.tabActivo = tab;
    this.filtroNumeroControl = '';
    this.archivoTesisLiberacion = null;
    this.mensajeLiberacionProducto = '';
    this.limpiarSeleccionDocumento();
    const seccion = this.tabASeccionAlerta(tab);
    if (seccion) this.quitarFlashTab(seccion);
    this.cargarBandeja();
  }

  private tabASeccionAlerta(tab: string): SeccionAlerta | null {
    if (
      tab === 'pendientes' ||
      tab === 'en_correccion' ||
      tab === 'anteproyecto' ||
      tab === 'liberacion_producto' ||
      tab === 'sinodales'
    ) {
      return tab;
    }
    return null;
  }

  private conteoPendienteSeccion(c: DepartamentoCounts, seccion: SeccionAlerta): number {
    switch (seccion) {
      case 'pendientes':
        return c.pendientes ?? 0;
      case 'en_correccion':
        return c.en_correccion ?? 0;
      case 'anteproyecto':
        return c.anteproyecto ?? 0;
      case 'liberacion_producto':
        return c.liberacion_producto ?? 0;
      case 'sinodales':
        return c.sinodales_por_asignar ?? 0;
    }
  }

  /** Si el conteo pendiente de una sección sube → esa pestaña parpadea. */
  private detectarNovedadesPorSeccion(next: DepartamentoCounts): void {
    const secciones: SeccionAlerta[] = [
      'pendientes',
      'en_correccion',
      'anteproyecto',
      'liberacion_producto',
      'sinodales',
    ];
    if (!this.baselineCountsListo) {
      for (const s of secciones) {
        this.snapshotCountsPendientes[s] = this.conteoPendienteSeccion(next, s);
      }
      this.baselineCountsListo = true;
      return;
    }
    for (const s of secciones) {
      const actual = this.conteoPendienteSeccion(next, s);
      const prev = this.snapshotCountsPendientes[s] ?? 0;
      this.snapshotCountsPendientes[s] = actual;
      if (actual > prev) this.activarFlashTab(s);
    }
  }

  private detectarFilasNuevas(items: DepartamentoListItem[]): void {
    const tab = this.tabActivo;
    const ids = items.map((i) => i.id);
    const vistos = this.idsVistosPorTab.get(tab);
    if (!vistos) {
      this.idsVistosPorTab.set(tab, new Set(ids));
      return;
    }
    for (const id of ids) {
      if (!vistos.has(id)) this.activarFlashFila(id);
      vistos.add(id);
    }
  }

  private activarFlashTab(seccion: SeccionAlerta): void {
    this.tabsConNovedad = new Set(this.tabsConNovedad).add(seccion);
    const prev = this.flashTabTimers.get(seccion);
    if (prev) clearTimeout(prev);
    this.flashTabTimers.set(
      seccion,
      setTimeout(
        () => this.quitarFlashTab(seccion),
        DepartamentoAcademicoComponent.DURACION_FLASH_MS,
      ),
    );
  }

  private quitarFlashTab(seccion: SeccionAlerta): void {
    const timer = this.flashTabTimers.get(seccion);
    if (timer) {
      clearTimeout(timer);
      this.flashTabTimers.delete(seccion);
    }
    if (!this.tabsConNovedad.has(seccion)) return;
    const next = new Set(this.tabsConNovedad);
    next.delete(seccion);
    this.tabsConNovedad = next;
  }

  private activarFlashFila(id: string): void {
    this.idsFilaNueva = new Set(this.idsFilaNueva).add(id);
    const prev = this.flashFilaTimers.get(id);
    if (prev) clearTimeout(prev);
    this.flashFilaTimers.set(
      id,
      setTimeout(
        () => this.quitarFlashFila(id),
        DepartamentoAcademicoComponent.DURACION_FLASH_MS,
      ),
    );
  }

  private quitarFlashFila(id: string): void {
    const timer = this.flashFilaTimers.get(id);
    if (timer) {
      clearTimeout(timer);
      this.flashFilaTimers.delete(id);
    }
    if (!this.idsFilaNueva.has(id)) return;
    const next = new Set(this.idsFilaNueva);
    next.delete(id);
    this.idsFilaNueva = next;
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
        this.cargarBandeja();
        this.limpiarSeleccionDocumento();
      },
      error: (err: { error?: { error?: string } }) => {
        this.liberandoProductoId = null;
        this.mensajeLiberacionProducto = textoErrorUsuario(err, `No se pudo liberar ${modalidad}.`);
      },
    });
  }

  volverInicio(): void {
    this.router.navigate(['/home']);
  }

  /** El Anexo XXXII solo pide observaciones. Los asesores salen del expediente. */
  descargarAnexo32(id: string, ev?: Event): void {
    ev?.stopPropagation();
    const item = this.lista.find((i) => i.id === id) ?? (this.seleccionado?.id === id ? this.seleccionado : null);
    if (!item || this.descargando32Id === id) return;
    this.xxxiiModalItem = item;
    this.xxxiiObservaciones = item.observaciones_anexo32?.trim() ?? '';
    this.xxxiiError = '';
  }

  private slugDepartamentoActual(): string {
    return this.segmentoCoordinacion?.trim()
      || this.authService.getUsuario()?.segmento_academico?.trim()
      || '';
  }

  nombresAsesoresXxxii(item: DepartamentoListItem): string {
    const partes = this.esResidencia(item.modalidad)
      ? [item.asesor_interno, item.asesor_externo]
      : this.esModalidadDirector(item.modalidad)
        ? [item.asesor_firma, item.revisor_1, item.revisor_2]
        : [item.asesor_interno, item.asesor_externo, item.asesor_firma];
    const nombres = partes.map((n) => n?.trim() ?? '').filter((n) => n.length > 0);
    return nombres.length > 0 ? `Asesores: ${nombres.join(', ')}` : 'No hay asesores guardados en el expediente.';
  }

  cerrarModalXxxii(): void {
    if (this.descargando32Id) return;
    this.xxxiiModalItem = null;
    this.xxxiiError = '';
  }

  confirmarDescargaXxxii(): void {
    const item = this.xxxiiModalItem;
    if (!item || this.descargando32Id) return;
    const observaciones = this.xxxiiObservaciones.trim();
    this.xxxiiError = '';
    this.descargando32Id = item.id;
    this.egresadoService.descargarHoja32(item.id, observaciones, this.slugDepartamentoActual()).subscribe({
      next: ({ blob, fileName }) => {
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url; a.download = fileName; a.click();
        URL.revokeObjectURL(url);
        this.descargando32Id = null;
        const actual = this.lista.find((i) => i.id === item.id);
        if (actual) {
          actual.observaciones_anexo32 = observaciones;
          if (!actual.fecha_generacion_hoja_32) actual.fecha_generacion_hoja_32 = new Date().toISOString();
          if (this.seleccionado?.id === item.id) this.seleccionado = { ...actual };
        }
        this.xxxiiModalItem = null;
      },
      error: () => {
        this.descargando32Id = null;
        this.xxxiiError = 'No se pudo generar el anexo.';
      },
    });
  }

  /** Abre el selector de asesor y revisores antes de generar el Anexo XXXIII. */
  descargarAnexo33(id: string, ev?: Event): void {
    ev?.stopPropagation();
    const item = this.lista.find((i) => i.id === id) ?? (this.seleccionado?.id === id ? this.seleccionado : null);
    if (!item || this.descargando33Id === id) return;
    this.xxxiiiModalItem = item;
    const residencia = this.esResidencia(item.modalidad);
    this.xxxiiiAsesor = residencia ? '' : (item.asesor_firma?.trim() ?? '');
    this.xxxiiiRevisor1 = residencia ? '' : (item.revisor_1?.trim() ?? '');
    this.xxxiiiRevisor2 = residencia ? '' : (item.revisor_2?.trim() ?? '');
    this.xxxiiiError = '';
    if (residencia || this.docentesLista.length > 0) return;
    this.xxxiiiCargando = true;
    this.docenteService.listar().subscribe({
      next: (docentes) => {
        this.docentesLista = docentes;
        this.xxxiiiCargando = false;
      },
      error: (err) => {
        this.xxxiiiCargando = false;
        this.xxxiiiError = textoErrorUsuario(err, 'No se pudo cargar la lista de docentes.');
      },
    });
  }

  cerrarModalXxxiii(): void {
    if (this.descargando33Id) return;
    this.xxxiiiModalItem = null;
    this.xxxiiiError = '';
  }

  confirmarDescargaXxxiii(): void {
    const item = this.xxxiiiModalItem;
    if (!item || this.descargando33Id) return;
    const residencia = this.esResidencia(item.modalidad);
    const asesor = residencia ? (item.asesor_interno?.trim() ?? '') : this.xxxiiiAsesor.trim();
    const revisor1 = residencia ? '' : this.xxxiiiRevisor1.trim();
    const revisor2 = residencia ? '' : this.xxxiiiRevisor2.trim();
    this.xxxiiiError = '';
    this.descargando33Id = item.id;
    this.egresadoService.descargarHoja33(item.id, { asesor, revisor1, revisor2 }, this.slugDepartamentoActual()).subscribe({
      next: ({ blob, fileName }) => {
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url; a.download = fileName; a.click();
        URL.revokeObjectURL(url);
        this.descargando33Id = null;
        const actual = this.lista.find((i) => i.id === item.id);
        if (actual) {
          actual.asesor_firma = asesor;
          actual.revisor_1 = revisor1;
          actual.revisor_2 = revisor2;
          if (!actual.fecha_generacion_hoja_33) actual.fecha_generacion_hoja_33 = new Date().toISOString();
          if (this.seleccionado?.id === item.id) this.seleccionado = { ...actual };
        }
        this.xxxiiiModalItem = null;
      },
      error: () => {
        this.descargando33Id = null;
        this.xxxiiiError = 'No se pudo generar el anexo.';
      },
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
        this.cargarBandeja();
      },
      error: (err: { error?: { error?: string; message?: string }; message?: string }) => {
        this.error = textoErrorUsuario(err, 'No se pudo liberar.');
        this.liberandoResidenciaId = null;
      },
    });
  }

  revertirLiberacionResidencia(id: string, ev?: Event): void {
    ev?.stopPropagation();
    if (this.revirtiendoId === id) return;
    if (!confirm('¿Revertir la liberación de residencia? El expediente volverá al estado anterior.')) return;
    this.revirtiendoId = id;
    this.error = '';
    this.egresadoService.revertirLiberacionResidencia(id).subscribe({
      next: () => {
        this.revirtiendoId = null;
        this.cargarBandeja();
        if (this.seleccionado?.id === id) this.limpiarSeleccionDocumento();
      },
      error: (err: { error?: { error?: string } }) => {
        this.revirtiendoId = null;
        this.error = textoErrorUsuario(err, 'No se pudo revertir la liberación.');
      },
    });
  }

  revertirRegistradoAnteproyecto(item: DepartamentoListItem, ev?: Event): void {
    ev?.stopPropagation();
    if (this.revirtiendoId === item.id) return;
    if (!confirm('¿Revertir el registro del anteproyecto? Volverá a pendiente de marcar como registrado.')) return;
    this.revirtiendoId = item.id;
    this.error = '';
    this.egresadoService.revertirRegistradoAnteproyecto(item.id).subscribe({
      next: () => {
        this.revirtiendoId = null;
        this.cargarBandeja();
        if (this.seleccionado?.id === item.id) this.limpiarSeleccionDocumento();
      },
      error: (err: { error?: { error?: string } }) => {
        this.revirtiendoId = null;
        this.error = textoErrorUsuario(err, 'No se pudo revertir el registro.');
      },
    });
  }

  revertirLiberacionProducto(item: DepartamentoListItem, ev?: Event): void {
    ev?.stopPropagation();
    if (this.revirtiendoId === item.id) return;
    if (!confirm('¿Revertir la liberación de producto? Se eliminará la tesis subida y volverá a pendiente.')) return;
    this.revirtiendoId = item.id;
    this.error = '';
    this.mensajeLiberacionProducto = '';
    this.egresadoService.revertirLiberacionProducto(item.id).subscribe({
      next: () => {
        this.revirtiendoId = null;
        this.archivoTesisLiberacion = null;
        this.cargarBandeja();
        if (this.seleccionado?.id === item.id) this.limpiarSeleccionDocumento();
      },
      error: (err: { error?: { error?: string } }) => {
        this.revirtiendoId = null;
        this.mensajeLiberacionProducto = textoErrorUsuario(err, 'No se pudo revertir la liberación.');
      },
    });
  }

  revertirAprobacionCoordinacion(item: DepartamentoListItem, ev?: Event): void {
    ev?.stopPropagation();
    if (this.revirtiendoId === item.id) return;
    if (!confirm('¿Revertir la aprobación? El expediente volverá al estado anterior.')) return;
    this.revirtiendoId = item.id;
    this.error = '';
    this.egresadoService.revertirAprobacionCoordinacion(item.id).subscribe({
      next: () => {
        this.revirtiendoId = null;
        this.cargarBandeja();
        if (this.seleccionado?.id === item.id) this.limpiarSeleccionDocumento();
      },
      error: (err: { error?: { error?: string } }) => {
        this.revirtiendoId = null;
        this.error = textoErrorUsuario(err, 'No se pudo revertir la aprobación.');
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

  sinodalesConcluidos(item: DepartamentoListItem): boolean {
    return !!item.fecha_confirmacion_entrega_anexo_9_3;
  }

  get sinodalesSoloLectura(): boolean {
    return !!this.sinodalesModalItem && this.sinodalesConcluidos(this.sinodalesModalItem);
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
      error: (err) => {
        this.sinodalesCargando = false;
        this.sinodalesError = textoErrorUsuario(err, 'No se pudieron cargar los datos. Intenta de nuevo.');
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
    if (!item || this.sinodalesSoloLectura) return;
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
          this.cargarBandeja();
        },
        error: (err: { error?: { error?: string }; message?: string }) => {
          this.sinodalesGuardando = false;
          this.sinodalesError = textoErrorUsuario(err, 'No se pudo guardar.');
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
