import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { BehaviorSubject, Observable, of, catchError, shareReplay, switchMap } from 'rxjs';
import { tap } from 'rxjs/operators';
import { environment } from '../../environments/environment';

// ── Tipos para gestión CRUD (pantalla coordinador) ────────────────────────────

export interface CatalogoItem {
  id: string;
  tipo: string;
  nombre: string;
  activo: boolean;
  orden: number;
  mesesVigencia: number | null;
  esResidencia: boolean;
  tipoMentores: string | null;
  slug: string | null;
  carreras: string[];
}

export interface CatalogoCreateRequest {
  tipo: string;
  nombre: string;
  orden?: number;
  mesesVigencia?: number | null;
  esResidencia?: boolean;
  tipoMentores?: string | null;
  slug?: string | null;
  carreras?: string[];
}

// ── Interfaces públicas ────────────────────────────────────────────────────────

export interface ModalidadCatalogo {
  nombre: string;
  mesesVigencia: number | null;
  esResidencia: boolean;
  /** true cuando la modalidad aplica ÚNICAMENTE en el flujo "Curso de titulación" (ej. Monografía). */
  esCursoTitulacion?: boolean;
  /** "residencia" | "estandar" | "ninguno" | null (null = derivar de esResidencia) */
  tipoMentores: string | null;
}

export interface DepartamentoCatalogo {
  nombre: string;
  slug: string | null;
  carreras: string[];
}

// ── Fallbacks (copia exacta de datos.ts y segmentos-academicos.ts) ─────────────
// Se usan si el backend no responde. Mantenerlos sincronizados con el seed.

const CARRERAS_FALLBACK: string[] = [
  'INGENIERIA EN AGRONOMÍA',
  'LICENCIATURA EN BIOLOGÍA',
  'INGENIERIA FORESTAL',
  'INGENIERIA INFORMÁTICA',
  'INGENIERIA EN TECNOLOGIA DE LA INFORMACION Y COMUNICACION ',
  'INGENIERIA EN CIENCIA DE DATOS',
  'INGENIERIA SISTEMAS COMPUTACIONALES',
  'INGENIERIA AMBIENTAL',
  'INGENIERIA EN GESTIÓN EMPRESARIAL (VIRTUAL)',
];

const NIVELES_FALLBACK: string[] = ['Licenciatura', 'Maestría', 'Posgrado'];

const MODALIDADES_FALLBACK: ModalidadCatalogo[] = [
  { nombre: 'Tesis',                     mesesVigencia: 18,   esResidencia: false, esCursoTitulacion: false, tipoMentores: 'estandar'   },
  { nombre: 'Tesina',                    mesesVigencia: 18,   esResidencia: false, esCursoTitulacion: true,  tipoMentores: 'estandar'   },
  { nombre: 'Residencia Profesional',    mesesVigencia: 6,    esResidencia: true,  esCursoTitulacion: false, tipoMentores: 'residencia' },
  { nombre: 'CENEVAL',                   mesesVigencia: null, esResidencia: false, esCursoTitulacion: false, tipoMentores: 'ninguno'    },
  { nombre: 'Proyecto de Investigación', mesesVigencia: 12,   esResidencia: false, esCursoTitulacion: true,  tipoMentores: 'estandar'  },
  { nombre: 'Monografía',               mesesVigencia: 12,   esResidencia: false, esCursoTitulacion: true,  tipoMentores: 'estandar'  },
];

const DEPARTAMENTOS_FALLBACK: DepartamentoCatalogo[] = [
  {
    nombre: 'Carreras virtuales',
    slug: 'virtuales',
    carreras: ['INGENIERIA SISTEMAS COMPUTACIONALES', 'INGENIERIA EN GESTIÓN EMPRESARIAL (VIRTUAL)'],
  },
  {
    nombre: 'Ingenierías',
    slug: 'ingenierias',
    carreras: ['INGENIERIA EN AGRONOMÍA', 'INGENIERIA FORESTAL', 'INGENIERIA AMBIENTAL'],
  },
  {
    nombre: 'Departamento Económico-Administrativo',
    slug: 'economico_administrativo',
    carreras: [
      'INGENIERIA INFORMÁTICA',
      'INGENIERIA EN TECNOLOGIA DE LA INFORMACION Y COMUNICACION ',
      'INGENIERIA EN CIENCIA DE DATOS',
    ],
  },
  {
    nombre: 'Departamento de ciencias básicas',
    slug: 'ciencias_basicas',
    carreras: ['LICENCIATURA EN BIOLOGÍA'],
  },
];

// ── Servicio ───────────────────────────────────────────────────────────────────

const API = `${environment.apiUrl}/api/catalogos`;

@Injectable({ providedIn: 'root' })
export class CatalogoService {

  // Trigger de refresco: emitir void fuerza una nueva petición HTTP en todos los observables.
  private readonly _refresh$ = new BehaviorSubject<void>(undefined);

  readonly carreras$: Observable<string[]> = this._refresh$.pipe(
    switchMap(() => this.http.get<string[]>(`${API}/carreras`).pipe(catchError(() => of(CARRERAS_FALLBACK)))),
    shareReplay({ bufferSize: 1, refCount: false }),
  );

  readonly niveles$: Observable<string[]> = this._refresh$.pipe(
    switchMap(() => this.http.get<string[]>(`${API}/niveles`).pipe(catchError(() => of(NIVELES_FALLBACK)))),
    shareReplay({ bufferSize: 1, refCount: false }),
  );

  readonly modalidades$: Observable<ModalidadCatalogo[]> = this._refresh$.pipe(
    switchMap(() => this.http.get<ModalidadCatalogo[]>(`${API}/modalidades`).pipe(
      catchError(() => of(MODALIDADES_FALLBACK)),
      tap(lista => (this.modalidadesCached = lista.length ? lista : MODALIDADES_FALLBACK)),
    )),
    shareReplay({ bufferSize: 1, refCount: false }),
  );

  readonly departamentos$: Observable<DepartamentoCatalogo[]> = this._refresh$.pipe(
    switchMap(() => this.http.get<DepartamentoCatalogo[]>(`${API}/departamentos`).pipe(
      catchError(() => of(DEPARTAMENTOS_FALLBACK)),
      tap(lista => (this.departamentosCached = lista.length ? lista : DEPARTAMENTOS_FALLBACK)),
    )),
    shareReplay({ bufferSize: 1, refCount: false }),
  );

  // Cachés sincrónicas para lookups en lógica de negocio sin necesidad de async.
  private modalidadesCached: ModalidadCatalogo[] = MODALIDADES_FALLBACK;
  private departamentosCached: DepartamentoCatalogo[] = DEPARTAMENTOS_FALLBACK;

  constructor(private http: HttpClient) {
    // Suscripciones internas: mantienen los cachés sincrónicos actualizados al refrescar.
    this.modalidades$.subscribe();
    this.departamentos$.subscribe();
  }

  /** Invalida el caché y fuerza un nuevo fetch en todos los componentes suscritos. */
  refresh(): void {
    this._refresh$.next();
  }

  /**
   * Devuelve los meses de vigencia de una modalidad.
   * Usa el caché o cae al fallback si la modalidad no está cargada aún.
   */
  mesesVigencia(nombreModalidad: string): number | null {
    const m = this.modalidadesCached.find(
      x => x.nombre.trim().toLowerCase() === nombreModalidad.trim().toLowerCase()
    );
    if (m !== undefined) return m.mesesVigencia;
    // Fallback idéntico al backend
    const lower = nombreModalidad.trim().toLowerCase();
    if (lower.includes('residencia')) return 6;
    if (lower.includes('monograf'))   return 12;
    if (lower.includes('tesina'))     return 12;
    if (lower.includes('tesis'))      return 12;
    if (lower.includes('curso'))      return 12;
    if (lower.includes('investigaci')) return 12;
    if (lower.includes('ceneval'))    return null;
    return 12;
  }

  /**
   * Indica si la modalidad es Residencia Profesional.
   * Usa el caché; si no está cargado aún, compara por nombre.
   */
  esResidencia(nombreModalidad: string): boolean {
    const m = this.modalidadesCached.find(
      x => x.nombre.trim().toLowerCase() === nombreModalidad.trim().toLowerCase()
    );
    return m?.esResidencia ?? nombreModalidad.trim().toLowerCase().includes('residencia');
  }

  /** Modalidad CENEVAL: recepción solicitud/testimonio y flujo compartido desde 9.1 (como residencia). */
  esCeneval(nombreModalidad: string): boolean {
    const m = this.modalidadesCached.find(
      x => x.nombre.trim().toLowerCase() === nombreModalidad.trim().toLowerCase()
    );
    if (m !== undefined) return m.nombre.trim().toLowerCase().includes('ceneval');
    return nombreModalidad.trim().toLowerCase().includes('ceneval');
  }

  /**
   * Devuelve el tipo de mentores para una modalidad:
   * "residencia" → asesor interno + externo
   * "estandar"   → director + asesor 1 + asesor 2
   * "ninguno"    → sin campos de mentores
   */
  tipoMentoresPorNombre(nombreModalidad: string): 'residencia' | 'estandar' | 'ninguno' {
    const m = this.modalidadesCached.find(
      x => x.nombre.trim().toLowerCase() === nombreModalidad.trim().toLowerCase()
    );
    if (m?.tipoMentores) return m.tipoMentores as 'residencia' | 'estandar' | 'ninguno';
    // fallback: derivar de esResidencia
    const esRes = m?.esResidencia ?? nombreModalidad.trim().toLowerCase().includes('residencia');
    return esRes ? 'residencia' : 'estandar';
  }

  // ── CRUD para coordinador ──────────────────────────────────────────────────

  listarTodos(): Observable<CatalogoItem[]> {
    return this.http.get<CatalogoItem[]>(API);
  }

  crearItem(request: CatalogoCreateRequest): Observable<CatalogoItem> {
    return this.http.post<CatalogoItem>(API, request);
  }

  actualizarItem(id: string, request: CatalogoCreateRequest): Observable<CatalogoItem> {
    return this.http.put<CatalogoItem>(`${API}/${id}`, request);
  }

  desactivarItem(id: string): Observable<unknown> {
    return this.http.delete(`${API}/${id}`);
  }

  activarItem(id: string): Observable<unknown> {
    return this.http.post(`${API}/${id}/activar`, {});
  }

  /**
   * Devuelve las carreras asignadas a un departamento por su slug (sincrónico, usa caché).
   */
  carrerasPorSlugSync(slug: string): string[] {
    return this.departamentosCached.find(d => d.slug === slug)?.carreras ?? [];
  }

  /**
   * Devuelve el nombre del departamento por su slug (sincrónico, usa caché).
   */
  nombreDepartamentoPorSlug(slug: string): string | null {
    return this.departamentosCached.find(d => d.slug === slug)?.nombre ?? null;
  }

  /**
   * Infiere el nombre del departamento buscando cuál contiene todas las carreras dadas.
   * Útil cuando el usuario tiene carreras_asignadas pero no slug almacenado.
   */
  inferirNombreDepartamentoPorCarreras(carreras: string[]): string | null {
    const normalizadas = carreras.map(c => this.normalizarCarreraKey(c)).filter(Boolean);
    if (!normalizadas.length) return null;
    for (const dep of this.departamentosCached) {
      const set = new Set(dep.carreras.map(c => this.normalizarCarreraKey(c)));
      if (normalizadas.every(c => set.has(c))) return dep.nombre;
    }
    return null;
  }

  /** Clave normalizada para comparar carreras (sin acentos, espacios colapsados). */
  normalizarCarreraKey(carrera: string): string {
    return (carrera ?? '')
      .trim()
      .normalize('NFD')
      .replace(/\p{M}/gu, '')
      .replace(/\s+/g, ' ')
      .toLowerCase();
  }

  /** Slug del departamento académico al que pertenece la carrera (p. ej. economico_administrativo). */
  slugPorCarreraSync(carrera: string): string | null {
    const key = this.normalizarCarreraKey(carrera);
    if (!key) return null;
    for (const dep of this.departamentosCached) {
      if (dep.carreras.some(c => this.normalizarCarreraKey(c) === key)) {
        return dep.slug ?? null;
      }
    }
    return null;
  }

  nombreDepartamentoPorCarreraSync(carrera: string): string | null {
    const key = this.normalizarCarreraKey(carrera);
    if (!key) return null;
    for (const dep of this.departamentosCached) {
      if (dep.carreras.some(c => this.normalizarCarreraKey(c) === key)) {
        return dep.nombre;
      }
    }
    return null;
  }
}
