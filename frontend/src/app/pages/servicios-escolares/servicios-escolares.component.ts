import { Component, OnInit } from '@angular/core';
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

@Component({
  selector: 'app-servicios-escolares',
  standalone: true,
  imports: [CommonModule, FormsModule, HeaderComponent],
  templateUrl: './servicios-escolares.component.html',
  styleUrl: './servicios-escolares.component.css',
})
export class ServiciosEscolaresComponent implements OnInit {
  tabActivo: TabServiciosEscolares = 'pendientes';
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

  constructor(
    private serviciosEscolaresService: ServiciosEscolaresService,
    private auth: AuthService,
    private router: Router,
  ) {}

  get mostrarVolverInicio(): boolean {
    return this.auth.isCoordinador();
  }

  volverInicio(): void {
    this.router.navigate(['/home']);
  }

  ngOnInit(): void {
    this.cargarLista();
    this.actualizarContadorOpuesto();
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
    this.cargandoDetalle = true;
    this.mensajeDetalle = '';
    this.error = '';
    this.serviciosEscolaresService.detalle(item.id).subscribe({
      next: (d) => {
        this.detalle = d;
        this.cargandoDetalle = false;
      },
      error: () => {
        this.cargandoDetalle = false;
        this.mensajeDetalle = 'No se pudo cargar el detalle del expediente.';
      },
    });
  }

  confirmando = false;

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
      error: () => {
        this.confirmando = false;
        this.mensajeDetalle = 'No se pudo confirmar. Intenta de nuevo.';
      },
    });
  }

  formatearFecha(iso?: string | null): string {
    if (!iso) return '—';
    const d = new Date(iso);
    if (Number.isNaN(d.getTime())) return iso;
    const dd = String(d.getDate()).padStart(2, '0');
    const mm = String(d.getMonth() + 1).padStart(2, '0');
    const yyyy = d.getFullYear();
    const hh = String(d.getHours()).padStart(2, '0');
    const min = String(d.getMinutes()).padStart(2, '0');
    if (iso.includes('T')) return `${dd}/${mm}/${yyyy}, ${hh}:${min}`;
    return `${dd}/${mm}/${yyyy}`;
  }

  private cargarLista(): void {
    this.cargandoLista = true;
    this.error = '';
    this.serviciosEscolaresService.listar(this.tabActivo, this.buscarControl).subscribe({
      next: (lista) => {
        this.items = lista;
        this.cargandoLista = false;
        if (this.tabActivo === 'pendientes') this.totalPendientes = lista.length;
        else this.totalAtendidos = lista.length;
      },
      error: () => {
        this.cargandoLista = false;
        this.items = [];
        this.error = 'No se pudo cargar la bandeja. Intenta de nuevo.';
      },
    });
  }

  private actualizarContadorOpuesto(): void {
    const opuesto: TabServiciosEscolares = this.tabActivo === 'pendientes' ? 'atendidos' : 'pendientes';
    this.serviciosEscolaresService.listar(opuesto).subscribe({
      next: (lista) => {
        if (opuesto === 'pendientes') this.totalPendientes = lista.length;
        else this.totalAtendidos = lista.length;
      },
    });
  }
}
