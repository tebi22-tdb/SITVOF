import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { Router } from '@angular/router';
import { HeaderComponent } from '../../layout/header/header.component';
import { AuthService } from '../../services/auth.service';
import { EgresadoService, EgresadoItem } from '../../services/egresado.service';
import { CatalogoService } from '../../services/catalogo.service';
import {
  calcularVistaPlazosResidencia,
  calcularVistaPlazosNoResidencia,
} from '../../core/plazos-titulacion-no-residencia';

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
  barPctStr: string;
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

interface EtapaItem {
  label: string;
  count: number;
  color: string;
}

interface ActividadRecienteItem {
  name: string;
  initials: string;
  avatarBg: string;
  hace: string;
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

  private items: DashItem[] = [];
  private rawItems: EgresadoItem[] = [];

  urgentesDisplay: UrgentItem[] = [];
  modalidadesDisplay: BarDato[] = [];
  carrerasDisplay: BarDato[] = [];

  readonly modulosNav: ModuloNav[] = [
    {
      title: 'Inicio del proceso', sub: 'Registro de egresados',
      descripcion: 'Registra nuevos egresados al proceso de titulación. Selecciona la modalidad, captura los datos del estudiante y genera el Anexo XXXI de inicio.',
      color: '#2563eb', key: 'alta', soloPleno: false,
    },
    {
      title: 'Seguimiento del proceso', sub: 'Envíos, anexos, sinodales',
      descripcion: 'Consulta y gestiona el avance de cada egresado: envío de documentos, generación de anexos XXXII/XXXIII, asignación de sinodales y acto protocolario.',
      color: '#0d9488', key: 'seguimiento', soloPleno: false,
    },
    {
      title: 'Alta de docentes', sub: 'Registra docentes',
      descripcion: 'Registra a los docentes del plantel que pueden fungir como director, asesor o vocal en el acto protocolario de titulación.',
      color: '#7c3aed', key: 'docentes', soloPleno: true,
    },
    {
      title: 'Revisiones de documentos', sub: 'Estado de la revisión',
      descripcion: 'Revisa los expedientes enviados por los egresados: acepta o rechaza documentos con observaciones antes de liberarlos al departamento académico.',
      color: '#d97706', key: 'revisiones', soloPleno: true,
    },
    {
      title: 'Departamento académico', sub: 'Ingenierías, Económico',
      descripcion: 'Gestión por departamento (Ingenierías, Económico-Administrativo, Virtuales). Visualiza revisiones y libera trabajos hacia la coordinación.',
      color: '#16a34a', key: 'departamento', soloPleno: true,
    },
    {
      title: 'Servicios escolares', sub: 'Constancias de no inconv.',
      descripcion: 'Genera y controla las constancias de no inconveniencia (Anexo 9.2) que emite Servicios Escolares para habilitar el siguiente paso del trámite.',
      color: '#ea580c', key: 'escolares', soloPleno: true,
    },
    {
      title: 'Gestión de cuentas', sub: 'Correo y contraseña',
      descripcion: 'Administra las credenciales de egresados y personal: restablece contraseñas, reactiva cuentas bloqueadas y asigna o modifica roles de acceso.',
      color: '#475569', key: 'cuentas', soloPleno: true,
    },
    {
      title: 'Repositorio de documentos', sub: 'Proyectos de egresados',
      descripcion: 'Consulta el repositorio público de proyectos de egresados titulados, organizados por carrera, modalidad y generación.',
      color: '#4f46e5', key: 'repositorio', soloPleno: true,
    },
    {
      title: 'Configuración institucional', sub: 'Imagen TECNM y jefes',
      descripcion: 'Configura los datos institucionales del plantel: imagen corporativa TECNM, jefes de departamento para la firma en Anexos 32 y 33.',
      color: '#0891b2', key: 'config', soloPleno: false,
    },
    {
      title: 'Configuración de catálogos', sub: 'Carreras, niveles, modalid.',
      descripcion: 'Administra los catálogos del sistema: carreras, niveles educativos, modalidades de titulación y departamentos académicos disponibles.',
      color: '#db2777', key: 'catalogos', soloPleno: true,
    },
  ];

  private readonly AVATAR_COLORS = ['#2563eb', '#7c3aed', '#0d9488', '#db2777', '#ea580c', '#0891b2', '#16a34a', '#4f46e5'];

  // ── KPI counters ──
  get totalItems(): number    { return this.items.length; }
  get totalEnTiempo(): number { return this.items.filter(i => i.estado === 'en_tiempo').length; }
  get totalRezagados(): number{ return this.items.filter(i => i.estado === 'rezagado').length; }
  get totalVencidos(): number { return this.items.filter(i => i.estado === 'vencido').length; }
  get totalConcluidos(): number{ return this.items.filter(i => i.estado === 'concluido').length; }

  get urgVenc(): number { return this.totalVencidos; }
  get urgRez(): number  { return this.totalRezagados; }

  // ── Sidebar: embudo de etapas ──
  get embudoEtapas(): EtapaItem[] {
    const activos = this.rawItems.filter(e => !e.fecha_confirmacion_documentacion_escaneada_recibida);
    const reg = activos.filter(e => !e.fecha_enviado_departamento_academico).length;
    const dep = activos.filter(e =>  e.fecha_enviado_departamento_academico && !e.fecha_recibido_registro_liberacion).length;
    const lib = activos.filter(e =>  e.fecha_recibido_registro_liberacion   && !e.fecha_creacion_anexo_9_3).length;
    const sin = activos.filter(e =>  e.fecha_creacion_anexo_9_3).length;
    return [
      { label: 'Registrado',       count: reg, color: '#64748b' },
      { label: 'En departamento',  count: dep, color: '#2563eb' },
      { label: 'Liberado',         count: lib, color: '#7c3aed' },
      { label: 'Sinodales / acto', count: sin, color: '#16a34a' },
    ];
  }

  // ── Sidebar: últimos 3 actualizados ──
  get actividadReciente(): ActividadRecienteItem[] {
    return [...this.rawItems]
      .filter(e => e.fecha_actualizacion)
      .sort((a, b) => new Date(b.fecha_actualizacion!).getTime() - new Date(a.fecha_actualizacion!).getTime())
      .slice(0, 3)
      .map(e => ({
        name:     e.nombre || e.numero_control,
        initials: this.initialsFromName(e.nombre || ''),
        avatarBg: this.hueColor(e.nombre || e.numero_control),
        hace:     this.tiempoAtras(e.fecha_actualizacion!),
      }));
  }

  // ── Sidebar: sin movimiento >30 días (no concluidos) ──
  get sinMovimientoCount(): number {
    const hace30 = Date.now() - 30 * 24 * 60 * 60 * 1000;
    return this.rawItems.filter(e =>
      !e.fecha_confirmacion_documentacion_escaneada_recibida &&
      e.fecha_actualizacion &&
      new Date(e.fecha_actualizacion).getTime() < hace30
    ).length;
  }

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
  get modulosVisibles(): ModuloNav[] {
    return this.esApoyoTitulacion
      ? this.modulosNav.filter(m => !m.soloPleno)
      : this.modulosNav;
  }

  // ── User info ──
  get nombreUsuario(): string {
    return this.auth.getUsuario()?.nombre ?? this.auth.getUsuario()?.username ?? '—';
  }

  get rolDisplay(): string {
    const rol = (this.auth.getUsuario()?.rol ?? '').toLowerCase();
    if (rol.includes('coordinador')) return 'Coordinación Académica';
    if (rol.includes('division'))    return 'Div. de Estudios Profesionales';
    if (rol.includes('apoyo'))       return 'Apoyo a Titulación';
    if (rol.includes('admin'))       return 'Administrador';
    return 'Personal Institucional';
  }

  get avatarIniciales(): string {
    const n = (this.auth.getUsuario()?.nombre ?? '').trim();
    const partes = n.split(/\s+/);
    if (partes.length >= 2) return (partes[0][0] + partes[1][0]).toUpperCase();
    if (partes[0]?.length)  return partes[0].slice(0, 2).toUpperCase();
    return 'CA';
  }

  get fechaHoy(): string {
    return new Date().toLocaleDateString('es-MX', {
      weekday: 'long', day: 'numeric', month: 'long', year: 'numeric',
    });
  }

  get esApoyoTitulacion(): boolean {
    return this.auth.isApoyoTitulacion();
  }

  constructor(
    private router: Router,
    private auth: AuthService,
    private egresadoService: EgresadoService,
    private catalogoService: CatalogoService,
  ) {}

  ngOnInit(): void {
    this.egresadoService.listar({ aplicar_scope_departamento: false }).subscribe({
      next: (lista) => {
        this.rawItems = lista;
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
      repositorio:  ['/repositorio'],
      config:       ['/home/config-institucional'],
      catalogos:    ['/home/catalogos'],
    };
    if (routes[key]) this.router.navigate(routes[key]);
  }

  irSeguimientoProceso(): void {
    this.router.navigate(['/home/seguimiento-proceso']);
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
    const barPct    = isVencido
      ? 4
      : Math.min(90, Math.max(10, Math.round(((item.diasRestantes ?? 0) / 180) * 100)));

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
      barPctStr:   barPct + '%',
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

  private initialsFromName(nombre: string): string {
    const partes = nombre.trim().split(/\s+/).filter(Boolean);
    if (partes.length >= 2) return (partes[0][0] + partes[1][0]).toUpperCase();
    return nombre.slice(0, 2).toUpperCase() || '—';
  }

  private tiempoAtras(iso: string): string {
    const diff = Date.now() - new Date(iso).getTime();
    const h = Math.floor(diff / 3_600_000);
    if (h < 1)  return 'hace menos de 1 h';
    if (h < 24) return `hace ${h} h`;
    const d = Math.floor(h / 24);
    return `hace ${d} día${d !== 1 ? 's' : ''}`;
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
    const colors = ['#4f46e5', '#0d9488', '#db2777', '#ea580c', '#0891b2', '#16a34a'];
    return entries.map(([label, count], i) => ({
      label,
      count,
      pctStr: Math.round((count / max) * 100) + '%',
      color: colors[i % colors.length],
    }));
  }

  private mapear(e: EgresadoItem): DashItem {
    const hoy      = new Date();
    const modalidad = e.modalidad?.trim() || '—';
    const esRes    = this.catalogoService.esResidencia(modalidad);
    const esCeneval = this.catalogoService.esCeneval(modalidad);

    if (e.fecha_confirmacion_documentacion_escaneada_recibida) {
      return { id: e.id, alumno: e.nombre || '—', noControl: e.numero_control || '—',
               carrera: e.carrera || '—', modalidad, estado: 'concluido', diasRestantes: null };
    }

    if (esCeneval) {
      return { id: e.id, alumno: e.nombre || '—', noControl: e.numero_control || '—',
               carrera: e.carrera || '—', modalidad, estado: 'en_tiempo', diasRestantes: null };
    }

    const plazos = esRes
      ? calcularVistaPlazosResidencia(e, hoy)
      : calcularVistaPlazosNoResidencia(e, hoy);

    return {
      id:            e.id,
      alumno:        e.nombre || '—',
      noControl:     e.numero_control || '—',
      carrera:       e.carrera || '—',
      modalidad,
      estado:        plazos.estadoGlobal,
      diasRestantes: plazos.diasHastaLimiteMasCercano,
    };
  }
}
