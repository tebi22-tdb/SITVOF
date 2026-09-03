import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { HeaderComponent } from '../../layout/header/header.component';
import { AuthService } from '../../services/auth.service';
import { EgresadoService, EgresadoItem } from '../../services/egresado.service';
import { ConfigInstitucionalService } from '../../services/config-institucional.service';

type EstadoItem = 'en_tiempo' | 'rezagado' | 'vencido' | 'concluido';

interface DashItem {
  id: string;
  alumno: string;
  noControl: string;
  carrera: string;
  modalidad: string;
  estado: EstadoItem;
  diasRestantes: number | null;
}

interface UrgentItem {
  id: string;
  name: string;
  control: string;
  carrera: string;
  modalidad: string;
  initials: string;
  avatarBg: string;
  barColor: string;
  statusLabel: string;
  statusBg: string;
  statusColor: string;
}

interface BarDato {
  label: string;
  count: number;
  pctStr: string;
  color: string;
}

interface DonutSeg {
  dasharray: string;
  dashoffset: string;
  color: string;
}

interface ModuloNav {
  title: string;
  sub: string;
  descripcion: string;
  color: string;
  key: string;
  soloPleno: boolean;
}

@Component({
  selector: 'app-coordinador-inicio',
  standalone: true,
  imports: [CommonModule, HeaderComponent],
  templateUrl: './coordinador-inicio.component.html',
  styleUrl: './coordinador-inicio.component.css',
})
export class CoordinadorInicioComponent implements OnInit {
  cargando = true;
  error = '';

  /** Se muestra un aviso en el panel si el último intento de envío de correo (credenciales, avisos, sinodales…) falló. */
  correoFallando = false;
  correoUltimoError = '';

  private items: DashItem[] = [];

  urgentesDisplay: UrgentItem[] = [];
  modalidadesDisplay: BarDato[] = [];
  carrerasDisplay: BarDato[] = [];

  // Orden = prioridad/frecuencia de uso real (más usado primero), agrupado por
  // color para que la posición refuerce la categoría en vez de interrumpirla:
  // Grupo A — Proceso (azul, tints de --color-marca-*) → Grupo B — Administración (--color-admin*).
  // La intensidad del tono también sigue la prioridad dentro de cada grupo:
  // más oscuro = más usado, más claro = más ocasional.
  // Los colores semánticos (rojo/ámbar/verde de vencido-rezagado-en_tiempo) nunca se reusan aquí.
  readonly modulosNav: ModuloNav[] = [
    // ── Grupo A: Proceso de titulación ──
    {
      title: 'Seguimiento del proceso', sub: 'Envíos, anexos, sinodales',
      descripcion: 'Consulta y gestiona el avance de cada egresado: envío de documentos, generación de anexos XXXII/XXXIII, asignación de sinodales y acto protocolario.',
      color: 'var(--color-marca-oscuro)', key: 'seguimiento', soloPleno: false,
    },
    {
      title: 'Departamento académico', sub: 'Ingenierías, Económico',
      descripcion: 'Gestión por departamento (Ingenierías, Económico-Administrativo, Virtuales). Visualiza revisiones y libera trabajos hacia la coordinación.',
      color: 'var(--color-marca-oscuro)', key: 'departamento', soloPleno: true,
    },
    {
      title: 'Inicio del proceso', sub: 'Registro de egresados',
      descripcion: 'Registra nuevos egresados al proceso de titulación. Selecciona la modalidad, captura los datos del estudiante y genera el Anexo XXXI de inicio.',
      color: 'var(--color-marca-medio)', key: 'alta', soloPleno: false,
    },
    {
      title: 'Revisiones de documentos', sub: 'Estado de la revisión',
      descripcion: 'Revisa los expedientes enviados por los egresados: acepta o rechaza documentos con observaciones antes de liberarlos al departamento académico.',
      color: 'var(--color-marca-medio)', key: 'revisiones', soloPleno: true,
    },
    {
      title: 'Servicios escolares', sub: 'Constancias de no inconv.',
      descripcion: 'Genera y controla las constancias de no inconveniencia (Anexo 9.2) que emite Servicios Escolares para habilitar el siguiente paso del trámite.',
      color: 'var(--color-marca-claro)', key: 'escolares', soloPleno: true,
    },
    // ── Grupo B: Administración / consulta ──
    {
      title: 'Gestión de cuentas', sub: 'Correo y contraseña',
      descripcion: 'Administra las credenciales de egresados y personal: restablece contraseñas, reactiva cuentas bloqueadas y asigna o modifica roles de acceso.',
      color: 'var(--color-admin)', key: 'cuentas', soloPleno: true,
    },
    {
      title: 'Alta de docentes', sub: 'Registra docentes',
      descripcion: 'Registra a los docentes del plantel que pueden fungir como director, asesor o vocal en el acto protocolario de titulación.',
      color: 'var(--color-admin)', key: 'docentes', soloPleno: true,
    },
    {
      title: 'Reportes y analítica', sub: 'KPIs, tendencias, tiempos',
      descripcion: 'Consulta indicadores consolidados del proceso de titulación: distribución por modalidad y carrera, tiempos promedio por etapa y tasa de aprobación en primera revisión.',
      color: 'var(--color-admin-claro)', key: 'reportes', soloPleno: true,
    },
    {
      title: 'Repositorio de documentos', sub: 'Proyectos de egresados',
      descripcion: 'Consulta el repositorio público de proyectos de egresados titulados, organizados por carrera, modalidad y generación.',
      color: 'var(--color-admin-claro)', key: 'repositorio', soloPleno: true,
    },
    {
      title: 'Configuración de catálogos', sub: 'Carreras, niveles, modalid.',
      descripcion: 'Administra los catálogos del sistema: carreras, niveles educativos, modalidades de titulación y departamentos académicos disponibles.',
      color: 'var(--color-admin-claro)', key: 'catalogos', soloPleno: true,
    },
    {
      title: 'Configuración institucional', sub: 'Imagen TECNM y jefes',
      descripcion: 'Configura los datos institucionales del plantel: imagen corporativa TECNM, jefes de departamento para la firma en Anexos 32 y 33.',
      color: 'var(--color-admin-claro)', key: 'config', soloPleno: true,
    },
  ];

  // Paleta de avatares: tints de marca + acento admin, ya no colores random.
  // hueColor() (abajo) sigue siendo el hash — solo cambió de dónde saca los tonos.
  private readonly AVATAR_COLORS = [
    'var(--color-marca-oscuro)', 'var(--color-marca-medio)', 'var(--color-marca-claro)',
    'var(--color-admin)', 'var(--color-admin-claro)',
  ];

  // ── KPI counters ──
  get totalItems(): number    { return this.items.length; }
  get totalEnTiempo(): number { return this.items.filter(i => i.estado === 'en_tiempo').length; }
  get totalRezagados(): number{ return this.items.filter(i => i.estado === 'rezagado').length; }
  get totalVencidos(): number { return this.items.filter(i => i.estado === 'vencido').length; }
  get totalConcluidos(): number{ return this.items.filter(i => i.estado === 'concluido').length; }

  // ── Donut SVG segments ──
  get donutSegments(): DonutSeg[] {
    if (!this.totalItems) return [];
    const gap = 1.5;
    const segs = [
      { pct: (this.totalEnTiempo  / this.totalItems) * 100, color: '#16a34a' },
      { pct: (this.totalRezagados / this.totalItems) * 100, color: '#d97706' },
      { pct: (this.totalVencidos  / this.totalItems) * 100, color: '#dc2626' },
      { pct: (this.totalConcluidos/ this.totalItems) * 100, color: '#94a3b8' },
    ];
    let offset = 0;
    return segs.map(s => {
      const d = Math.max(0, s.pct - gap);
      const result: DonutSeg = {
        dasharray: `${d.toFixed(2)} ${(100 - d).toFixed(2)}`,
        dashoffset: `${(-offset).toFixed(2)}`,
        color: s.color,
      };
      offset += s.pct;
      return result;
    });
  }

  // ── Modules visible per role ──
  // Solo administrador llega a este componente: apoyo_titulacion y DEP quedan
  // fuera desde la ruta (ver noApoyoTitulacionGuard en app.routes.ts), así que
  // no hace falta filtrar modulosNav por rol — siempre se ve completo.
  get modulosVisibles(): ModuloNav[] {
    return this.modulosNav;
  }

  constructor(
    private router: Router,
    private auth: AuthService,
    private egresadoService: EgresadoService,
    private configInstitucionalService: ConfigInstitucionalService,
  ) {}

  ngOnInit(): void {
    this.configInstitucionalService.getEstadoCorreo().subscribe({
      next: (estado) => {
        this.correoFallando = !estado.ok;
        this.correoUltimoError = estado.ultimo_error ?? '';
      },
      // Si el propio chequeo falla (ej. red), no molestamos con un aviso de correo que no confirmamos.
      error: () => {},
    });
    this.egresadoService.listar({ aplicar_scope_departamento: false }).subscribe({
      next: (lista) => {
        this.items = lista.map(e => this.mapear(e));
        this.urgentesDisplay    = this.buildUrgentesDisplay();
        this.modalidadesDisplay = this.buildDistribucion('modalidad');
        this.carrerasDisplay    = this.buildDistribucion('carrera');
        this.cargando = false;
      },
      error: () => {
        this.error = 'No se pudieron cargar los datos del dashboard.';
        this.cargando = false;
      },
    });
  }

  irModulo(key: string): void {
    const routes: Record<string, string[]> = {
      alta:         ['/home/alta'],
      seguimiento:  ['/home/seguimiento-proceso'],
      docentes:     ['/home/alta-docentes'],
      revisiones:   ['/home/revisiones'],
      departamento: ['/home/departamento-academico'],
      escolares:    ['/servicios-escolares'],
      cuentas:      ['/home/gestion-cuentas'],
      reportes:     ['/home/reportes'],
      repositorio:  ['/repositorio'],
      config:       ['/home/config-institucional'],
      catalogos:    ['/home/catalogos'],
    };
    if (routes[key]) this.router.navigate(routes[key]);
  }

  /** Sin `carrera`, va a la lista completa. Con `carrera`, aterriza ya filtrado
   *  (seguimiento-proceso lee el query param igual que ya hace con `buscar`). */
  irSeguimientoProceso(carrera?: string): void {
    this.router.navigate(['/home/seguimiento-proceso'], carrera ? { queryParams: { carrera } } : undefined);
  }

  // ── Private builders ──

  private buildUrgentesDisplay(): UrgentItem[] {
    return this.items
      .filter(i => i.estado === 'vencido' || i.estado === 'rezagado')
      .sort((a, b) => {
        if (a.estado === 'vencido' && b.estado !== 'vencido') return -1;
        if (b.estado === 'vencido' && a.estado !== 'vencido') return 1;
        return (a.diasRestantes ?? 999) - (b.diasRestantes ?? 999);
      })
      .slice(0, 8)
      .map(i => this.buildUrgente(i));
  }

  private buildUrgente(item: DashItem): UrgentItem {
    const isVencido = item.estado === 'vencido';
    const barColor  = isVencido ? '#dc2626' : '#d97706';

    const partes   = item.alumno.split(/\s+/).filter(Boolean);
    const initials = partes.length >= 2
      ? (partes[0][0] + partes[1][0]).toUpperCase()
      : item.alumno.slice(0, 2).toUpperCase();

    return {
      id:          item.id,
      name:        item.alumno,
      control:     item.noControl,
      carrera:     item.carrera,
      modalidad:   item.modalidad,
      initials,
      avatarBg:    this.hueColor(item.alumno),
      barColor,
      statusLabel: isVencido ? 'Vencido' : `${item.diasRestantes}d`,
      statusBg:    isVencido ? '#fee2e2' : '#fef3c7',
      statusColor: isVencido ? '#991b1b' : '#92400e',
    };
  }

  private hueColor(name: string): string {
    let h = 0;
    for (let i = 0; i < name.length; i++) h = ((h * 31) + name.charCodeAt(i)) >>> 0;
    return this.AVATAR_COLORS[h % this.AVATAR_COLORS.length];
  }

  private buildDistribucion(field: 'modalidad' | 'carrera'): BarDato[] {
    const map = new Map<string, number>();
    for (const item of this.items) {
      const key = (field === 'modalidad' ? item.modalidad : item.carrera) || '—';
      map.set(key, (map.get(key) ?? 0) + 1);
    }
    const maxEntries = field === 'modalidad' ? 5 : 6;
    const entries = [...map.entries()].sort((a, b) => b[1] - a[1]).slice(0, maxEntries);
    const max = Math.max(...entries.map(e => e[1]), 1);
    // Antes eran 6 hex random; ahora tints de marca. La barra ya comunica la
    // proporción con su largo — el color aquí solo diferencia visualmente filas
    // consecutivas, no codifica información propia.
    const colors = [
      'var(--color-marca-oscuro)', 'var(--color-marca-medio)', 'var(--color-marca-claro)',
      'var(--color-admin)', 'var(--color-admin-claro)', 'var(--color-marca-medio)',
    ];
    return entries.map(([label, count], i) => ({
      label,
      count,
      pctStr: Math.round((count / max) * 100) + '%',
      color: colors[i % colors.length],
    }));
  }

  /**
   * El backend ya manda `estado_plazo`/`dias_restantes_plazo` calculados (ver
   * PlazoTitulacionCalculator.kt) — este componente solo pinta, ya no recalcula.
   */
  private mapear(e: EgresadoItem): DashItem {
    return {
      id:            e.id,
      alumno:        e.nombre || '—',
      noControl:     e.numero_control || '—',
      carrera:       e.carrera || '—',
      modalidad:     e.modalidad?.trim() || '—',
      estado:        e.estado_plazo as EstadoItem,
      diasRestantes: e.dias_restantes_plazo ?? null,
    };
  }
}
