import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { HeaderComponent } from '../../layout/header/header.component';
import { DocenteService, DocenteItem } from '../../services/docente.service';

@Component({
  selector: 'app-alta-docentes',
  standalone: true,
  imports: [CommonModule, FormsModule, HeaderComponent],
  templateUrl: './alta-docentes.component.html',
  styleUrl: './alta-docentes.component.css',
})
export class AltaDocentesComponent implements OnInit {
  docentes: DocenteItem[] = [];
  cargando = true;
  error = '';

  modoForm: 'nuevo' | 'editar' | null = null;
  docenteSeleccionado: DocenteItem | null = null;

  formNombre = '';
  formCorreo = '';
  formCedula = '';
  formGenero = '';
  guardando = false;
  eliminando = false;
  errorForm = '';
  mensajeExito = '';

  constructor(
    private docenteService: DocenteService,
    private router: Router,
  ) {}

  volverInicio(): void {
    this.router.navigate(['/home']);
  }

  ngOnInit(): void {
    this.cargar();
  }

  cargar(): void {
    this.cargando = true;
    this.error = '';
    this.docenteService.listar().subscribe({
      next: (items) => {
        this.docentes = items;
        this.cargando = false;
      },
      error: () => {
        this.error = 'No se pudo cargar la lista de docentes.';
        this.cargando = false;
      },
    });
  }

  abrirNuevo(): void {
    this.modoForm = 'nuevo';
    this.docenteSeleccionado = null;
    this.formNombre = '';
    this.formCorreo = '';
    this.formCedula = '';
    this.formGenero = '';
    this.errorForm = '';
    this.mensajeExito = '';
  }

  seleccionar(docente: DocenteItem): void {
    this.modoForm = 'editar';
    this.docenteSeleccionado = docente;
    this.formNombre = docente.nombreCompleto;
    this.formCorreo = docente.correo;
    this.formCedula = docente.cedula;
    this.formGenero = docente.genero || '';
    this.errorForm = '';
    this.mensajeExito = '';
  }

  cancelar(): void {
    this.modoForm = null;
    this.docenteSeleccionado = null;
    this.errorForm = '';
    this.mensajeExito = '';
  }

  guardar(): void {
    this.errorForm = '';
    this.mensajeExito = '';
    const req = {
      nombreCompleto: this.formNombre.trim(),
      correo: this.formCorreo.trim(),
      cedula: this.formCedula.trim(),
      genero: this.formGenero.trim(),
    };
    if (!req.nombreCompleto) { this.errorForm = 'El nombre completo es requerido.'; return; }
    if (!req.correo) { this.errorForm = 'El correo es requerido.'; return; }
    if (!req.cedula) { this.errorForm = 'La cédula es requerida.'; return; }
    if (req.genero !== 'M' && req.genero !== 'F') { this.errorForm = 'Selecciona el género (M o F).'; return; }

    this.guardando = true;
    if (this.modoForm === 'nuevo') {
      this.docenteService.crear(req).subscribe({
        next: (d) => {
          this.guardando = false;
          this.docentes = [...this.docentes, d].sort((a, b) =>
            a.nombreCompleto.localeCompare(b.nombreCompleto),
          );
          this.mensajeExito = 'Docente registrado correctamente.';
          this.modoForm = null;
        },
        error: (err: { error?: { error?: string } }) => {
          this.guardando = false;
          this.errorForm = err?.error?.error ?? 'No se pudo guardar el docente.';
        },
      });
    } else if (this.modoForm === 'editar' && this.docenteSeleccionado) {
      this.docenteService.actualizar(this.docenteSeleccionado.id, req).subscribe({
        next: (d) => {
          this.guardando = false;
          this.docentes = this.docentes
            .map((x) => (x.id === d.id ? d : x))
            .sort((a, b) => a.nombreCompleto.localeCompare(b.nombreCompleto));
          this.docenteSeleccionado = d;
          this.mensajeExito = 'Docente actualizado correctamente.';
        },
        error: (err: { error?: { error?: string } }) => {
          this.guardando = false;
          this.errorForm = err?.error?.error ?? 'No se pudo actualizar el docente.';
        },
      });
    }
  }

  eliminar(): void {
    if (!this.docenteSeleccionado) return;
    this.eliminando = true;
    this.errorForm = '';
    this.docenteService.eliminar(this.docenteSeleccionado.id).subscribe({
      next: () => {
        this.eliminando = false;
        this.docentes = this.docentes.filter((x) => x.id !== this.docenteSeleccionado!.id);
        this.cancelar();
      },
      error: (err: { error?: { error?: string } }) => {
        this.eliminando = false;
        this.errorForm = err?.error?.error ?? 'No se pudo eliminar el docente.';
      },
    });
  }
}
