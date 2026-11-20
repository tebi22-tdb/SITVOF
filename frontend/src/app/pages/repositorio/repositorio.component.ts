import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RepositorioService, TituladoPublico } from '../../services/repositorio.service';
import { LOGO_ITVO, LOGO_TECNM } from '../../core/logos';
import { textoErrorUsuario } from '../../core/mensaje-conexion';

@Component({
  selector: 'app-repositorio',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './repositorio.component.html',
  styleUrl: './repositorio.component.css',
})
export class RepositorioComponent implements OnInit {
  readonly logoTecnm = LOGO_TECNM;
  readonly logoItvo = LOGO_ITVO;
  titulados: TituladoPublico[] = [];
  cargando = true;
  error = '';
  buscar = '';
  filtroCarrera = '';
  filtroModalidad = '';

  get carrerasDisponibles(): string[] {
    return [...new Set(this.titulados.map((t) => t.carrera))].filter(Boolean).sort();
  }

  get modalidadesDisponibles(): string[] {
    return [...new Set(this.titulados.map((t) => t.modalidad))].filter(Boolean).sort();
  }

  get resultados(): TituladoPublico[] {
    const term = this.buscar.trim().toLowerCase();
    return this.titulados.filter((t) => {
      if (this.filtroCarrera && t.carrera !== this.filtroCarrera) return false;
      if (this.filtroModalidad && t.modalidad !== this.filtroModalidad) return false;
      if (!term) return true;
      return (
        t.nombre_proyecto.toLowerCase().includes(term) ||
        t.nombre.toLowerCase().includes(term) ||
        t.carrera.toLowerCase().includes(term)
      );
    });
  }

  descargando = new Set<string>();

  constructor(readonly repositorioService: RepositorioService) {}

  verDocumento(egresadoId: string): void {
    if (this.descargando.has(egresadoId)) return;
    this.descargando.add(egresadoId);
    this.repositorioService.descargarDocumento(egresadoId).subscribe({
      next: (blob) => {
        this.descargando.delete(egresadoId);
        const url = URL.createObjectURL(blob);
        window.open(url, '_blank', 'noopener');
        setTimeout(() => URL.revokeObjectURL(url), 60_000);
      },
      error: (err) => {
        this.descargando.delete(egresadoId);
        this.error = textoErrorUsuario(err, 'No se pudo abrir el documento.');
      },
    });
  }

  ngOnInit(): void {
    this.repositorioService.listar().subscribe({
      next: (lista) => {
        this.titulados = lista;
        this.cargando = false;
      },
      error: (err) => {
        this.error = textoErrorUsuario(err, 'No se pudo cargar la lista. Intenta de nuevo.');
        this.cargando = false;
      },
    });
  }

  asesores(t: TituladoPublico): string {
    return [t.asesor_interno, t.asesor_externo, t.director, t.asesor_1, t.asesor_2]
      .filter(Boolean)
      .join(', ');
  }
}
