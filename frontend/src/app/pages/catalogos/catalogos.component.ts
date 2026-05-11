import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { HeaderComponent } from '../../layout/header/header.component';
import { CatalogoService, CatalogoItem, CatalogoCreateRequest } from '../../services/catalogo.service';

type TabTipo = 'carrera' | 'nivel' | 'modalidad' | 'departamento';

interface FormState {
  visible: boolean;
  modo: 'crear' | 'editar';
  editandoId: string | null;
  nombreAnterior: string;
  nombre: string;
  mesesVigencia: number | null;
  esResidencia: boolean;
  tipoMentores: string;
  slug: string;
  carrerasSeleccionadas: string[];
  departamentoId: string;
}

@Component({
  selector: 'app-catalogos',
  standalone: true,
  imports: [CommonModule, FormsModule, HeaderComponent],
  templateUrl: './catalogos.component.html',
  styleUrl: './catalogos.component.css',
})
export class CatalogosComponent implements OnInit {
  tabActivo: TabTipo = 'carrera';
  items: CatalogoItem[] = [];
  departamentosItems: CatalogoItem[] = [];
  cargando = false;
  guardando = false;
  mensaje = '';
  mensajeError = false;

  form: FormState = this.formInicial();

  readonly tabs: { id: TabTipo; label: string }[] = [
    { id: 'carrera',      label: 'Carreras' },
    { id: 'nivel',        label: 'Niveles' },
    { id: 'modalidad',    label: 'Modalidades' },
    { id: 'departamento', label: 'Departamentos' },
  ];

  get itemsActivos(): CatalogoItem[] {
    return this.items.filter(i => i.activo);
  }

  get itemsInactivos(): CatalogoItem[] {
    return this.items.filter(i => !i.activo);
  }

  get esTabDepartamento(): boolean { return this.tabActivo === 'departamento'; }
  get esTabModalidad(): boolean    { return this.tabActivo === 'modalidad'; }
  get esTabCarrera(): boolean      { return this.tabActivo === 'carrera'; }

  /** Carreras activas para los checkboxes del formulario de departamento. */
  get carrerasDisponibles(): string[] {
    return this.items
      .filter(i => i.tipo === 'carrera' && i.activo)
      .map(i => i.nombre);
  }

  constructor(
    private catalogoService: CatalogoService,
    private router: Router,
  ) {}

  ngOnInit(): void {
    this.cargar();
  }

  cambiarTab(tab: TabTipo): void {
    if (tab === this.tabActivo) return;
    this.tabActivo = tab;
    this.form = this.formInicial();
    this.mensaje = '';
    this.cargar();
  }

  cargar(): void {
    this.cargando = true;
    this.catalogoService.listarTodos().subscribe({
      next: todos => {
        this.departamentosItems = todos
          .filter(i => i.tipo === 'departamento' && i.activo)
          .sort((a, b) => a.nombre.localeCompare(b.nombre));

        this.items = todos
          .filter(i => i.tipo === this.tabActivo)
          .sort((a, b) => a.orden - b.orden || a.nombre.localeCompare(b.nombre));

        this.cargando = false;
      },
      error: () => {
        this.items = [];
        this.cargando = false;
        this.mostrarMensaje('No se pudo cargar la lista.', true);
      },
    });
  }

  abrirFormularioCrear(): void {
    this.form = this.formInicial();
    this.form.visible = true;
    this.form.modo = 'crear';
    this.mensaje = '';
  }

  abrirFormularioEditar(item: CatalogoItem): void {
    let departamentoId = '';
    if (this.esTabCarrera) {
      const dept = this.departamentosItems.find(d =>
        d.carreras.some(c => c.trim() === item.nombre.trim()),
      );
      departamentoId = dept?.id ?? '';
    }

    this.form = {
      visible: true,
      modo: 'editar',
      editandoId: item.id,
      nombreAnterior: item.nombre,
      nombre: item.nombre,
      mesesVigencia: item.mesesVigencia ?? null,
      esResidencia: item.esResidencia,
      tipoMentores: item.tipoMentores ?? (item.esResidencia ? 'residencia' : 'estandar'),
      slug: item.slug ?? '',
      carrerasSeleccionadas: [...item.carreras],
      departamentoId,
    };
    this.mensaje = '';
  }

  cancelarFormulario(): void {
    this.form = this.formInicial();
    this.mensaje = '';
  }

  guardar(): void {
    if (!this.form.nombre.trim()) {
      this.mostrarMensaje('El nombre es obligatorio.', true);
      return;
    }
    if (this.esTabCarrera && !this.form.departamentoId) {
      this.mostrarMensaje('Debes seleccionar el departamento al que pertenece la carrera.', true);
      return;
    }
    if (this.esTabDepartamento && !this.form.slug.trim()) {
      this.mostrarMensaje('La clave interna (slug) es obligatoria para departamentos.', true);
      return;
    }

    // Calcular orden automático: al final de los ítems activos existentes.
    const ordenAuto = this.form.modo === 'crear'
      ? (this.itemsActivos.length > 0 ? Math.max(...this.itemsActivos.map(i => i.orden)) + 1 : 0)
      : (this.items.find(i => i.id === this.form.editandoId)?.orden ?? 0);

    const request: CatalogoCreateRequest = {
      tipo: this.tabActivo,
      nombre: this.form.nombre.trim(),
      orden: ordenAuto,
      mesesVigencia: this.esTabModalidad ? this.form.mesesVigencia : undefined,
      esResidencia: this.esTabModalidad ? this.form.esResidencia : undefined,
      tipoMentores: this.esTabModalidad ? this.form.tipoMentores : undefined,
      slug: this.esTabDepartamento ? this.form.slug.trim() : undefined,
      carreras: this.esTabDepartamento ? this.form.carrerasSeleccionadas : undefined,
    };

    this.guardando = true;
    this.mensaje = '';

    if (this.esTabCarrera) {
      this.guardarCarrera(request);
    } else if (this.form.modo === 'crear') {
      this.catalogoService.crearItem(request).subscribe({
        next: () => this.finalizarGuardado('Elemento creado.'),
        error: err => this.finalizarGuardado(err?.error?.error ?? 'No se pudo crear.', true),
      });
    } else {
      this.catalogoService.actualizarItem(this.form.editandoId!, request).subscribe({
        next: () => this.finalizarGuardado('Elemento actualizado.'),
        error: err => this.finalizarGuardado(err?.error?.error ?? 'No se pudo actualizar.', true),
      });
    }
  }

  desactivar(item: CatalogoItem): void {
    if (!confirm(`¿Desactivar "${item.nombre}"? No aparecerá en nuevos registros pero los expedientes existentes no se ven afectados.`)) return;
    this.catalogoService.desactivarItem(item.id).subscribe({
      next: () => this.finalizarGuardado('Elemento desactivado.'),
      error: () => this.mostrarMensaje('No se pudo desactivar.', true),
    });
  }

  activar(item: CatalogoItem): void {
    this.catalogoService.activarItem(item.id).subscribe({
      next: () => this.finalizarGuardado('Elemento activado.'),
      error: () => this.mostrarMensaje('No se pudo activar.', true),
    });
  }

  toggleCarrera(carrera: string): void {
    const idx = this.form.carrerasSeleccionadas.indexOf(carrera);
    if (idx >= 0) this.form.carrerasSeleccionadas.splice(idx, 1);
    else this.form.carrerasSeleccionadas.push(carrera);
  }

  carreraSeleccionada(carrera: string): boolean {
    return this.form.carrerasSeleccionadas.includes(carrera);
  }

  nombreDeptDeCarrera(nombreCarrera: string): string {
    return this.departamentosItems.find(d =>
      d.carreras.some(c => c.trim() === nombreCarrera.trim())
    )?.nombre ?? '—';
  }

  volver(): void {
    this.router.navigate(['/home']);
  }

  // ── Lógica especial para carreras ───────────────────────────────────────────

  private guardarCarrera(request: CatalogoCreateRequest): void {
    const deptNuevo = this.departamentosItems.find(d => d.id === this.form.departamentoId);

    if (this.form.modo === 'crear') {
      this.catalogoService.crearItem(request).subscribe({
        next: carreraCreada => {
          if (deptNuevo) {
            this.actualizarDepartamento(deptNuevo, [...deptNuevo.carreras, carreraCreada.nombre], () =>
              this.finalizarGuardado('Carrera creada y asignada al departamento.'),
            );
          } else {
            this.finalizarGuardado('Carrera creada.');
          }
        },
        error: err => this.finalizarGuardado(err?.error?.error ?? 'No se pudo crear la carrera.', true),
      });
    } else {
      const nombreAnterior = this.form.nombreAnterior;
      const deptAnterior = this.departamentosItems.find(d =>
        d.carreras.some(c => c.trim() === nombreAnterior.trim()),
      );

      this.catalogoService.actualizarItem(this.form.editandoId!, request).subscribe({
        next: carreraActualizada => {
          this.actualizarDepartamentosTrasCambioCarrera(
            nombreAnterior, carreraActualizada.nombre, deptAnterior ?? null, deptNuevo ?? null,
          );
        },
        error: err => this.finalizarGuardado(err?.error?.error ?? 'No se pudo actualizar la carrera.', true),
      });
    }
  }

  private actualizarDepartamentosTrasCambioCarrera(
    nombreAnterior: string,
    nombreNuevo: string,
    deptAnterior: CatalogoItem | null,
    deptNuevo: CatalogoItem | null,
  ): void {
    const mismoDept = deptAnterior?.id === deptNuevo?.id;
    const mismoNombre = nombreAnterior.trim() === nombreNuevo.trim();

    if (mismoDept && mismoNombre) {
      // Sin cambios que afecten departamentos
      this.finalizarGuardado('Carrera actualizada.');
      return;
    }

    if (mismoDept && deptAnterior) {
      // Mismo depto, solo cambia el nombre: reemplazar en la lista
      const nuevasCarreras = deptAnterior.carreras.map(c =>
        c.trim() === nombreAnterior.trim() ? nombreNuevo : c,
      );
      this.actualizarDepartamento(deptAnterior, nuevasCarreras, () =>
        this.finalizarGuardado('Carrera actualizada.'),
      );
      return;
    }

    // Depto distinto: quitar del anterior y agregar al nuevo
    const pending = { count: (deptAnterior ? 1 : 0) + (deptNuevo ? 1 : 0) };
    const done = () => { if (--pending.count === 0) this.finalizarGuardado('Carrera actualizada y reasignada.'); };

    if (deptAnterior) {
      const filtradas = deptAnterior.carreras.filter(c => c.trim() !== nombreAnterior.trim());
      this.actualizarDepartamento(deptAnterior, filtradas, done);
    }
    if (deptNuevo) {
      const agregadas = [...deptNuevo.carreras, nombreNuevo];
      this.actualizarDepartamento(deptNuevo, agregadas, done);
    }
    if (!deptAnterior && !deptNuevo) {
      this.finalizarGuardado('Carrera actualizada.');
    }
  }

  private actualizarDepartamento(dept: CatalogoItem, carreras: string[], onDone: () => void): void {
    this.catalogoService.actualizarItem(dept.id, {
      tipo: 'departamento',
      nombre: dept.nombre,
      slug: dept.slug ?? '',
      orden: dept.orden,
      carreras,
    }).subscribe({ next: onDone, error: onDone });
  }

  private finalizarGuardado(texto: string, esError = false): void {
    this.guardando = false;
    this.form = this.formInicial();
    this.mostrarMensaje(texto, esError);
    this.catalogoService.refresh();
    this.cargar();
  }

  private mostrarMensaje(texto: string, esError = false): void {
    this.mensaje = texto;
    this.mensajeError = esError;
  }

  private formInicial(): FormState {
    return {
      visible: false,
      modo: 'crear',
      editandoId: null,
      nombreAnterior: '',
      nombre: '',
      mesesVigencia: null,
      esResidencia: false,
      tipoMentores: 'estandar',
      slug: '',
      carrerasSeleccionadas: [],
      departamentoId: '',
    };
  }
}
