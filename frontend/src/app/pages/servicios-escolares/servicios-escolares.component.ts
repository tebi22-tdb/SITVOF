import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { HeaderComponent } from '../../layout/header/header.component';
import { AuthService } from '../../services/auth.service';
import {
  ServiciosEscolaresBandejaItem,
  ServiciosEscolaresDetalle,
  ServiciosEscolaresService,
  TabServiciosEscolares,
} from '../../services/servicios-escolares.service';
import { textoErrorUsuario } from '../../core/mensaje-conexion';

const STORAGE_PENDIENTES_VISTOS = 'sit_se_pendientes_vistos';
/** Duración de la animación: 1.1s × 3 ciclos ≈ 3.3s */
const DURACION_RESALTE_MS = 3600;
const POLL_PENDIENTES_MS = 30_000;

@Component({
  selector: 'app-servicios-escolares',
  standalone: true,
  imports: [CommonModule, FormsModule, HeaderComponent],
  templateUrl: './servicios-escolares.component.html',
  styleUrl: './servicios-escolares.component.css',
})
export class ServiciosEscolaresComponent implements OnInit, OnDestroy {
  tabActivo: TabServiciosEscolares = 'pendientes';

  esCeneval(modalidad: string | undefined): boolean {
    return (modalidad || '').toLowerCase().includes('ceneval');
  }
  buscarControl = '';
  items: ServiciosEscolaresBandejaItem[] = [];
  detalle: ServiciosEscolaresDetalle | null = null;
  cargandoLista = false;
  cargandoDetalle = false;
  error = '';
  mensajeDetalle = '';

  /** Contadores de la otra pestaña (carga ligera al cambiar tab). */
  totalPendientes = 0;
  totalAtendidos = 0;

  /** IDs de pendientes que deben parpadear (animación ×3). */
  idsNuevosResaltar = new Set<string>();
  /** Hay al menos un pendiente nuevo: el badge de la pestaña también parpadea. */
  hayPendientesNuevos = false;

  private idsPendientesVistos = new Set<string>();
  private baselinePendientesListo = false;
  private pollTimer: ReturnType<typeof setInterval> | null = null;
  private resalteTimers = new Map<string, ReturnType<typeof setTimeout>>();

  constructor(
    private serviciosEscolaresService: ServiciosEscolaresService,
    private auth: AuthService,
    private router: Router,
  ) {}

  get mostrarVolverInicio(): boolean {
    return this.auth.isAdministrador();
  }

  /** Revertir atención: solo administrador, no el perfil de servicios escolares. */
  get puedeRevertirAtencion(): boolean {
    return this.auth.puedeRevertirPasoDepartamento();
  }

  volverInicio(): void {
    this.router.navigate(['/home']);
  }

  esNuevo(id: string): boolean {
    return this.idsNuevosResaltar.has(id);
  }

  ngOnInit(): void {
    this.cargarIdsVistosDesdeSesion();
    this.cargarLista();
    this.actualizarContadorOpuesto();
    this.pollTimer = setInterval(() => this.sondearPendientes(), POLL_PENDIENTES_MS);
  }

  ngOnDestroy(): void {
    if (this.pollTimer) clearInterval(this.pollTimer);
    this.resalteTimers.forEach((t) => clearTimeout(t));
    this.resalteTimers.clear();
  }

  cambiarTab(tab: TabServiciosEscolares): void {
    if (this.tabActivo === tab) return;
    this.tabActivo = tab;
    this.detalle = null;
    this.mensajeDetalle = '';
    this.cargarLista();
    this.actualizarContadorOpuesto();
  }

  buscar(): void {
    this.detalle = null;
    this.cargarLista();
  }

  limpiarBusqueda(): void {
    this.buscarControl = '';
    this.buscar();
  }

  seleccionar(item: ServiciosEscolaresBandejaItem): void {
    this.marcarComoVisto(item.id);
    this.cargandoDetalle = true;
    this.mensajeDetalle = '';
    this.error = '';
    this.serviciosEscolaresService.detalle(item.id).subscribe({
      next: (d) => {
        this.detalle = d;
        this.cargandoDetalle = false;
      },
      error: (err) => {
        this.cargandoDetalle = false;
        this.mensajeDetalle = textoErrorUsuario(err, 'No se pudo abrir el expediente.');
      },
    });
  }

  confirmando = false;
  revirtiendo = false;

  aceptarPendiente(): void {
    if (!this.detalle || this.tabActivo !== 'pendientes' || this.confirmando) return;
    this.confirmando = true;
    this.mensajeDetalle = '';
    this.serviciosEscolaresService.confirmar(this.detalle.id).subscribe({
      next: () => {
        this.confirmando = false;
        this.mensajeDetalle = '';
        this.cargarLista();
        this.actualizarContadorOpuesto();
        this.serviciosEscolaresService.detalle(this.detalle!.id).subscribe({
          next: (d) => (this.detalle = d),
        });
      },
      error: (err) => {
        this.confirmando = false;
        this.mensajeDetalle = textoErrorUsuario(err, 'No se pudo confirmar. Intenta de nuevo.');
      },
    });
  }

  revertirAtencion(): void {
    if (!this.detalle || this.tabActivo !== 'atendidos' || !this.puedeRevertirAtencion || this.revirtiendo) return;
    if (!this.detalle.fecha_aceptacion_servicios_escolares_anexo_9_2) return;
    if (!confirm('¿Revertir la atención? La solicitud volverá a pendientes.')) return;
    this.revirtiendo = true;
    this.mensajeDetalle = '';
    this.serviciosEscolaresService.revertir(this.detalle.id).subscribe({
      next: () => {
        this.revirtiendo = false;
        this.detalle = null;
        this.cargarLista();
        this.actualizarContadorOpuesto();
      },
      error: (err) => {
        this.revirtiendo = false;
        this.mensajeDetalle = textoErrorUsuario(err, 'No se pudo revertir la atención.');
      },
    });
  }

  descargando92 = false;

  descargarAnexo92(): void {
    if (!this.detalle || this.descargando92) return;
    if (this.detalle.fecha_aceptacion_servicios_escolares_anexo_9_2) return;
    this.descargando92 = true;
    this.mensajeDetalle = '';
    const id = this.detalle.id;
    this.serviciosEscolaresService.descargarAnexo92(id).subscribe({
      next: ({ blob, fileName }) => {
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = fileName;
        a.click();
        URL.revokeObjectURL(url);
        this.descargando92 = false;
        this.serviciosEscolaresService.detalle(id).subscribe({
          next: (d) => (this.detalle = d),
        });
      },
      error: (err) => {
        this.descargando92 = false;
        this.mensajeDetalle = textoErrorUsuario(err, 'No se pudo generar el anexo 9.2. Intenta de nuevo.');
      },
    });
  }

  formatearFecha(iso?: string | null): string {
    if (!iso) return '—';
    const dia = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso);
    if (!dia) return iso;
    if (/T00:00:00(\.0+)?Z$/.test(iso) || !iso.includes('T')) {
      return `${dia[3]}/${dia[2]}/${dia[1]}`;
    }
    const d = new Date(iso);
    if (Number.isNaN(d.getTime())) return iso;
    const partes = new Intl.DateTimeFormat('es-MX', {
      timeZone: 'America/Mexico_City',
      day: '2-digit',
      month: '2-digit',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
      hour12: false,
    }).formatToParts(d);
    const valor = (tipo: string) => partes.find(p => p.type === tipo)?.value ?? '';
    return `${valor('day')}/${valor('month')}/${valor('year')}, ${valor('hour')}:${valor('minute')}`;
  }

  private cargarLista(): void {
    this.cargandoLista = true;
    this.error = '';
    this.serviciosEscolaresService.listar(this.tabActivo, this.buscarControl).subscribe({
      next: (lista) => {
        this.items = lista;
        this.cargandoLista = false;
        if (this.tabActivo === 'pendientes') {
          this.totalPendientes = lista.length;
          this.procesarPendientesNuevos(lista);
        } else {
          this.totalAtendidos = lista.length;
        }
      },
      error: (err) => {
        this.cargandoLista = false;
        this.items = [];
        this.error = textoErrorUsuario(err, 'No se pudo cargar la lista. Intenta de nuevo.');
      },
    });
  }

  private actualizarContadorOpuesto(): void {
    const opuesto: TabServiciosEscolares = this.tabActivo === 'pendientes' ? 'atendidos' : 'pendientes';
    this.serviciosEscolaresService.listar(opuesto).subscribe({
      next: (lista) => {
        if (opuesto === 'pendientes') {
          this.totalPendientes = lista.length;
          this.procesarPendientesNuevos(lista, true);
        } else {
          this.totalAtendidos = lista.length;
        }
      },
    });
  }

  /** Sondeo en segundo plano para detectar llegadas sin recargar a mano. */
  private sondearPendientes(): void {
    if (this.tabActivo === 'pendientes') {
      this.serviciosEscolaresService.listar('pendientes', this.buscarControl).subscribe({
        next: (lista) => {
          this.items = lista;
          this.totalPendientes = lista.length;
          this.procesarPendientesNuevos(lista);
        },
      });
    } else {
      this.serviciosEscolaresService.listar('pendientes').subscribe({
        next: (lista) => {
          this.totalPendientes = lista.length;
          this.procesarPendientesNuevos(lista, true);
        },
      });
    }
  }

  /**
   * Primera carga: registra IDs actuales sin animar.
   * Cargas siguientes: IDs desconocidos parpadean 3 veces en rojo.
   * @param soloContador si true, no toca `items` (pestaña Atendidos / sondeo opuesto).
   */
  private procesarPendientesNuevos(
    lista: ServiciosEscolaresBandejaItem[],
    soloContador = false,
  ): void {
    const idsActuales = lista.map((i) => i.id);

    if (!this.baselinePendientesListo) {
      idsActuales.forEach((id) => this.idsPendientesVistos.add(id));
      this.baselinePendientesListo = true;
      this.guardarIdsVistosEnSesion();
      return;
    }

    const nuevos = idsActuales.filter((id) => !this.idsPendientesVistos.has(id));
    if (nuevos.length === 0) return;

    nuevos.forEach((id) => this.activarResalte(id));
    this.hayPendientesNuevos = this.idsNuevosResaltar.size > 0;

    if (!soloContador && this.tabActivo === 'pendientes') {
      // Los nuevos arriba para que el parpadeo se vea de inmediato.
      const setNuevos = new Set(nuevos);
      this.items = [
        ...lista.filter((i) => setNuevos.has(i.id)),
        ...lista.filter((i) => !setNuevos.has(i.id)),
      ];
    }
  }

  private activarResalte(id: string): void {
    if (this.idsNuevosResaltar.has(id)) return;
    this.idsNuevosResaltar.add(id);
    this.hayPendientesNuevos = true;

    const prev = this.resalteTimers.get(id);
    if (prev) clearTimeout(prev);

    const timer = setTimeout(() => {
      this.marcarComoVisto(id);
    }, DURACION_RESALTE_MS);
    this.resalteTimers.set(id, timer);
  }

  private marcarComoVisto(id: string): void {
    const timer = this.resalteTimers.get(id);
    if (timer) {
      clearTimeout(timer);
      this.resalteTimers.delete(id);
    }
    this.idsNuevosResaltar.delete(id);
    this.idsPendientesVistos.add(id);
    this.hayPendientesNuevos = this.idsNuevosResaltar.size > 0;
    this.guardarIdsVistosEnSesion();
  }

  private cargarIdsVistosDesdeSesion(): void {
    try {
      const raw = sessionStorage.getItem(STORAGE_PENDIENTES_VISTOS);
      if (!raw) return;
      const arr = JSON.parse(raw) as string[];
      if (Array.isArray(arr)) {
        arr.forEach((id) => this.idsPendientesVistos.add(id));
        if (arr.length > 0) this.baselinePendientesListo = true;
      }
    } catch {
      /* ignorar almacenamiento corrupto */
    }
  }

  private guardarIdsVistosEnSesion(): void {
    try {
      sessionStorage.setItem(
        STORAGE_PENDIENTES_VISTOS,
        JSON.stringify([...this.idsPendientesVistos]),
      );
    } catch {
      /* cuota / modo privado */
    }
  }
}
