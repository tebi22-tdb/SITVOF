import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { BaseChartDirective } from 'ng2-charts';
import { ChartConfiguration, ChartData } from 'chart.js';
import { CatalogoService, DepartamentoCatalogo } from '../../services/catalogo.service';
import { HeaderComponent } from '../../layout/header/header.component';
import {
  ListadoEgresadosFiltros,
  ReporteEgresadoListado,
  ReporteFiltros,
  ReporteResumenCoordinador,
  ReporteService,
} from '../../services/reporte.service';

const ETIQUETA_ESTADO: Record<string, string> = {
  en_tiempo: 'En tiempo',
  rezagado: 'Rezagado',
  vencido: 'Vencido',
  concluido: 'Concluido',
};

type Seccion = 'general' | 'listado';

@Component({
  selector: 'app-reportes',
  standalone: true,
  imports: [CommonModule, FormsModule, BaseChartDirective, HeaderComponent],
  templateUrl: './reportes.component.html',
  styleUrl: './reportes.component.css',
})
export class ReportesComponent implements OnInit {
  /** Expuesto para el template (altura dinámica de la gráfica de barras). */
  readonly Math = Math;

  seccion: Seccion = 'general';

  cargando = true;
  error = '';
  exportando = false;

  modalidades: string[] = [];
  carreras: string[] = [];
  departamentos: DepartamentoCatalogo[] = [];

  filtros: ReporteFiltros = {};
  resumen: ReporteResumenCoordinador | null = null;

  readonly ETIQUETA_ESTADO = ETIQUETA_ESTADO;

  /** Procesos registrados por mes — ver `ReporteService.calcularTendenciaMensual` (backend). */
  tendenciaLineData: ChartData<'line'> = { labels: [], datasets: [{ data: [], label: 'Procesos registrados' }] };
  readonly tendenciaOptions: ChartConfiguration<'line'>['options'] = {
    responsive: true,
    plugins: { legend: { display: false } },
    scales: { y: { beginAtZero: true, ticks: { precision: 0 } } },
  };

  /** Cuántos procesos alcanzaron cada hito del proceso — ver `ReporteService.calcularEmbudoEtapas` (backend). */
  embudoBarData: ChartData<'bar'> = { labels: [], datasets: [{ data: [], label: 'Procesos' }] };
  readonly barOptionsHorizontal: ChartConfiguration<'bar'>['options'] = {
    indexAxis: 'y',
    responsive: true,
    plugins: { legend: { display: false } },
    scales: { x: { beginAtZero: true } },
  };

  // ── Listado de egresados ──
  cargandoListado = false;
  errorListado = '';
  exportandoListado = false;
  listadoCargadoAlMenosUnaVez = false;
  listadoFiltros: ListadoEgresadosFiltros = {};
  listado: ReporteEgresadoListado[] = [];
  busquedaListado = '';

  constructor(
    private router: Router,
    private reporteService: ReporteService,
    private catalogoService: CatalogoService,
  ) {}

  ngOnInit(): void {
    this.catalogoService.modalidades$.subscribe((lista) => (this.modalidades = lista.map((m) => m.nombre)));
    this.catalogoService.carreras$.subscribe((lista) => (this.carreras = lista));
    this.catalogoService.departamentos$.subscribe((lista) => (this.departamentos = lista));
    this.cargar();
  }

  cambiarSeccion(s: Seccion): void {
    this.seccion = s;
    if (s === 'listado' && !this.listadoCargadoAlMenosUnaVez) this.cargarListado();
  }

  aplicarFiltros(): void {
    this.cargar();
    if (this.listadoCargadoAlMenosUnaVez) this.cargarListado();
  }

  limpiarFiltros(): void {
    this.filtros = {};
    this.aplicarFiltros();
  }

  aplicarFiltrosListado(): void {
    this.cargarListado();
  }

  limpiarFiltrosListado(): void {
    this.listadoFiltros = {};
    this.busquedaListado = '';
    this.cargarListado();
  }

  volver(): void {
    this.router.navigate(['/home']);
  }

  /** Búsqueda por nombre/número de control — sobre lo ya cargado, sin ida y vuelta al backend. */
  get listadoFiltrado(): ReporteEgresadoListado[] {
    const q = this.busquedaListado.trim().toLowerCase();
    if (!q) return this.listado;
    return this.listado.filter(
      (e) => e.nombre_completo.toLowerCase().includes(q) || e.numero_control.toLowerCase().includes(q),
    );
  }

  exportarExcel(): void {
    if (this.exportando) return;
    this.exportando = true;
    this.reporteService.exportarExcel(this.filtros).subscribe({
      next: (blob) => {
        this.exportando = false;
        this.descargarBlob(blob, 'reporte-titulacion.xlsx');
      },
      error: () => {
        this.exportando = false;
        this.error = 'No se pudo exportar el reporte a Excel.';
      },
    });
  }

  exportarListadoExcel(): void {
    if (this.exportandoListado) return;
    this.exportandoListado = true;
    this.reporteService.exportarListadoExcel(this.filtros, this.listadoFiltros).subscribe({
      next: (blob) => {
        this.exportandoListado = false;
        this.descargarBlob(blob, 'listado-egresados.xlsx');
      },
      error: () => {
        this.exportandoListado = false;
        this.errorListado = 'No se pudo exportar el listado a Excel.';
      },
    });
  }

  private descargarBlob(blob: Blob, nombreArchivo: string): void {
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = nombreArchivo;
    a.click();
    URL.revokeObjectURL(url);
  }

  private cargar(): void {
    this.cargando = true;
    this.error = '';
    this.reporteService.resumenCoordinador(this.filtros).subscribe({
      next: (r) => {
        this.resumen = r;
        this.construirGraficas(r);
        this.cargando = false;
      },
      error: () => {
        this.error = 'No se pudo cargar el reporte. Intenta de nuevo.';
        this.cargando = false;
      },
    });
  }

  private cargarListado(): void {
    this.cargandoListado = true;
    this.errorListado = '';
    this.listadoCargadoAlMenosUnaVez = true;
    this.reporteService.listadoEgresados(this.filtros, this.listadoFiltros).subscribe({
      next: (items) => {
        this.listado = items;
        this.cargandoListado = false;
      },
      error: () => {
        this.errorListado = 'No se pudo cargar el listado. Intenta de nuevo.';
        this.cargandoListado = false;
      },
    });
  }

  private construirGraficas(r: ReporteResumenCoordinador): void {
    this.tendenciaLineData = {
      labels: r.tendencia_mensual.map((m) => m.etiqueta),
      datasets: [
        {
          data: r.tendencia_mensual.map((m) => m.cantidad),
          label: 'Procesos registrados',
          borderColor: '#1f4c8f',
          backgroundColor: '#1f4c8f',
          tension: 0.2,
        },
      ],
    };

    this.embudoBarData = {
      labels: r.embudo_etapas.map((e) => e.etiqueta),
      datasets: [{ data: r.embudo_etapas.map((e) => e.cantidad), label: 'Procesos', backgroundColor: '#1f4c8f' }],
    };
  }
}
