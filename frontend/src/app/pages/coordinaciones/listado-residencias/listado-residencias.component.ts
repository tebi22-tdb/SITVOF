import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { HeaderComponent } from '../../../layout/header/header.component';
import {
  ResidenciaCoordinacionService,
  ResidenciaItem,
  ResidenciaStats,
  COORDINACIONES,
} from '../../../services/residencia-coordinacion.service';

@Component({
  selector: 'app-listado-residencias',
  standalone: true,
  imports: [CommonModule, FormsModule, HeaderComponent],
  templateUrl: './listado-residencias.component.html',
  styleUrl: './listado-residencias.component.css',
})
export class ListadoResienciasComponent implements OnInit {
  cargando = false;
  error = '';

  residencias: ResidenciaItem[] = [];
  stats: ResidenciaStats = { total: 0, activas: 0, pendientes_anexos: 0, finalizadas: 0 };

  busqueda = '';
  filtroCoordinacion = '';
  filtroTipo = '';
  filtroEstado = '';

  readonly coordinaciones = COORDINACIONES;

  paginaActual = 1;
  porPagina = 8;

  modalVisible = false;
  modalNombre = '';
  modalId = '';

  constructor(
    private service: ResidenciaCoordinacionService,
    private router: Router,
  ) {}

  ngOnInit(): void {
    this.cargarStats();
    this.cargar();
  }

  cargar(): void {
    this.cargando = true;
    this.error = '';
    this.service.listar({
      coordinacion: this.filtroCoordinacion || undefined,
      tipo_proyecto: this.filtroTipo || undefined,
      estado: this.filtroEstado || undefined,
      busqueda: this.busqueda || undefined,
    }).subscribe({
      next: (items) => {
        this.residencias = items;
        this.paginaActual = 1;
        this.cargando = false;
      },
      error: () => {
        this.error = 'No se pudo cargar la lista. Intenta de nuevo.';
        this.cargando = false;
      },
    });
  }

  cargarStats(): void {
    this.service.stats().subscribe({
      next: (s) => (this.stats = s),
      error: () => {},
    });
  }

  aplicarFiltros(): void {
    this.cargar();
  }

  get totalPaginas(): number {
    return Math.ceil(this.residencias.length / this.porPagina);
  }

  get residenciasPagina(): ResidenciaItem[] {
    const inicio = (this.paginaActual - 1) * this.porPagina;
    return this.residencias.slice(inicio, inicio + this.porPagina);
  }

  get numeroPaginas(): number[] {
    return Array.from({ length: this.totalPaginas }, (_, i) => i + 1);
  }

  get rangoMostrando(): string {
    const inicio = (this.paginaActual - 1) * this.porPagina + 1;
    const fin = Math.min(this.paginaActual * this.porPagina, this.residencias.length);
    return `${inicio}–${fin}`;
  }

  irPagina(p: number): void {
    if (p >= 1 && p <= this.totalPaginas) this.paginaActual = p;
  }

  irNuevo(): void {
    this.router.navigate(['/home/residencias/nuevo']);
  }

  irEditar(id: string): void {
    this.router.navigate(['/home/residencias/editar', id]);
  }

  abrirModal(id: string, nombre: string): void {
    this.modalId = id;
    this.modalNombre = nombre;
    this.modalVisible = true;
  }

  cerrarModal(): void {
    this.modalVisible = false;
    this.modalId = '';
    this.modalNombre = '';
  }

  confirmarEliminar(): void {
    if (!this.modalId) return;
    this.service.eliminar(this.modalId).subscribe({
      next: () => {
        this.cerrarModal();
        this.cargarStats();
        this.cargar();
      },
      error: () => {
        this.error = 'No se pudo eliminar el registro.';
        this.cerrarModal();
      },
    });
  }

  nombreCoordinacion(id: string): string {
    return this.coordinaciones.find((c) => c.id === id)?.nombre ?? id;
  }

  etiquetaEstado(estado: string): { texto: string; clase: string } {
    const mapa: Record<string, { texto: string; clase: string }> = {
      activa: { texto: 'Activa', clase: 'b-act' },
      pendiente_anexos: { texto: 'Anexos pendientes', clase: 'b-pend' },
      en_revision: { texto: 'En revisión', clase: 'b-rev' },
      finalizada: { texto: 'Finalizada', clase: 'b-fin' },
      cancelada: { texto: 'Cancelada', clase: 'b-can' },
    };
    return mapa[estado] ?? { texto: estado, clase: 'b-fin' };
  }

  etiquetaTipo(tipo: string): { texto: string; clase: string } {
    const mapa: Record<string, { texto: string; clase: string }> = {
      interno: { texto: 'Interno', clase: 'pill-mod' },
      externo: { texto: 'Externo', clase: 'pill-mod ext' },
      propio: { texto: 'Propio', clase: 'pill-mod own' },
    };
    return mapa[tipo] ?? { texto: tipo, clase: 'pill-mod' };
  }

  iniciales(nombre: string): string {
    return nombre
      .split(' ')
      .slice(0, 2)
      .map((p) => p[0])
      .join('')
      .toUpperCase();
  }

  formatearFecha(iso: string): string {
    if (!iso) return '—';
    const [y, m, d] = iso.split('-');
    return `${d}/${m}/${y}`;
  }

  fmt(iso: string): string {
    return this.formatearFecha(iso);
  }

  formatearBytes(bytes?: number): string {
    if (!bytes) return '';
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`;
    return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  }

  irInicio(): void {
    this.router.navigate(['/home']);
  }
}
