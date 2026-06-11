import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { HeaderComponent } from '../../../layout/header/header.component';
import {
  ResidenciaCoordinacionService,
  ResidenciaForm,
  COORDINACIONES,
  CARRERAS_POR_COORDINACION,
} from '../../../services/residencia-coordinacion.service';

@Component({
  selector: 'app-formulario-residencia',
  standalone: true,
  imports: [CommonModule, FormsModule, HeaderComponent],
  templateUrl: './formulario-residencia.component.html',
  styleUrl: './formulario-residencia.component.css',
})
export class FormularioResidenciaComponent implements OnInit {
  modoEdicion = false;
  editandoId = '';
  cargando = false;
  guardando = false;
  error = '';
  mensajeExito = '';

  form: ResidenciaForm = {
    numero_control: '',
    nombre_alumno: '',
    carrera: '',
    coordinacion: '',
    tipo_proyecto: '',
    nombre_proyecto: '',
    asesor_interno: '',
    asesor_externo: '',
    fecha_carta_aceptacion: '',
    fecha_inicio: '',
    fecha_fin: '',
    estado: 'activa',
  };

  anexo29: File | null = null;
  anexo30: File | null = null;
  anexo29Nombre = '';
  anexo30Nombre = '';
  anexo29Existente = false;
  anexo30Existente = false;

  readonly coordinaciones = COORDINACIONES;

  get carrerasDisponibles(): string[] {
    return CARRERAS_POR_COORDINACION[this.form.coordinacion] ?? [];
  }

  constructor(
    private service: ResidenciaCoordinacionService,
    private router: Router,
    private route: ActivatedRoute,
  ) {}

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (id) {
      this.modoEdicion = true;
      this.editandoId = id;
      this.cargarParaEditar(id);
    }
  }

  cargarParaEditar(id: string): void {
    this.cargando = true;
    this.service.obtenerPorId(id).subscribe({
      next: (r) => {
        this.form = {
          numero_control: r.numero_control,
          nombre_alumno: r.nombre_alumno,
          carrera: r.carrera,
          coordinacion: r.coordinacion,
          tipo_proyecto: r.tipo_proyecto,
          nombre_proyecto: r.nombre_proyecto,
          asesor_interno: r.asesor_interno,
          asesor_externo: r.asesor_externo ?? '',
          fecha_carta_aceptacion: r.fecha_carta_aceptacion,
          fecha_inicio: r.fecha_inicio,
          fecha_fin: r.fecha_fin,
          estado: r.estado,
        };
        this.anexo29Existente = r.tiene_anexo_29;
        this.anexo30Existente = r.tiene_anexo_30;
        this.cargando = false;
      },
      error: () => {
        this.error = 'No se pudo cargar el registro.';
        this.cargando = false;
      },
    });
  }

  onCoordinacionChange(): void {
    this.form.carrera = '';
  }

  onAnexo29Change(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0] ?? null;
    this.anexo29 = file;
    this.anexo29Nombre = file?.name ?? '';
  }

  onAnexo30Change(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0] ?? null;
    this.anexo30 = file;
    this.anexo30Nombre = file?.name ?? '';
  }

  guardar(): void {
    this.error = '';
    this.mensajeExito = '';

    if (!this.validar()) return;

    this.guardando = true;
    const obs = this.modoEdicion
      ? this.service.actualizar(this.editandoId, this.form, this.anexo29, this.anexo30)
      : this.service.crear(this.form, this.anexo29, this.anexo30);

    obs.subscribe({
      next: () => {
        this.guardando = false;
        this.router.navigate(['/home/residencias']);
      },
      error: (err) => {
        this.error = err?.error?.error ?? 'Ocurrió un error al guardar. Intenta de nuevo.';
        this.guardando = false;
      },
    });
  }

  cancelar(): void {
    this.router.navigate(['/home/residencias']);
  }

  irInicio(): void {
    this.router.navigate(['/home']);
  }

  private validar(): boolean {
    const f = this.form;
    if (!f.numero_control.trim()) { this.error = 'El número de control es requerido.'; return false; }
    if (!f.nombre_alumno.trim()) { this.error = 'El nombre del alumno es requerido.'; return false; }
    if (!f.coordinacion) { this.error = 'La coordinación es requerida.'; return false; }
    if (!f.carrera) { this.error = 'La carrera es requerida.'; return false; }
    if (!f.fecha_carta_aceptacion) { this.error = 'La fecha de carta de aceptación es requerida.'; return false; }
    if (!f.fecha_inicio) { this.error = 'La fecha de inicio es requerida.'; return false; }
    if (!f.fecha_fin) { this.error = 'La fecha de fin es requerida.'; return false; }
    if (!f.tipo_proyecto) { this.error = 'El tipo de proyecto es requerido.'; return false; }
    if (!f.nombre_proyecto.trim()) { this.error = 'El nombre del proyecto es requerido.'; return false; }
    if (!f.asesor_interno.trim()) { this.error = 'El asesor interno es requerido.'; return false; }
    return true;
  }
}
