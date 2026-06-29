import { Component, signal, computed, inject, Output, EventEmitter } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActoService } from '../../../services/acto.service';

interface Celda { day: number | null; date: Date | null; ocupados: number; }
interface Slot  { hora: string; fin: string; ocupado: boolean; }

export interface ActoAgendadoDto { fecha: string; horaInicio: string; horaFin: string; }

/** Bloques de 1 hora: 09:00–10:00 … 14:00–15:00 (6 en total). */
const HORAS = ['09:00', '10:00', '11:00', '12:00', '13:00', '14:00'];

@Component({
  selector: 'app-agendar-acto',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './agendar-acto.component.html',
  styleUrls: ['./agendar-acto.component.css'],
})
export class AgendarActoComponent {
  private api = inject(ActoService);

  @Output() cerrar   = new EventEmitter<void>();
  @Output() agendado = new EventEmitter<ActoAgendadoDto>();

  weekdays = ['Lun', 'Mar', 'Mié', 'Jue', 'Vie', 'Sáb', 'Dom'];
  meses    = ['Enero','Febrero','Marzo','Abril','Mayo','Junio','Julio','Agosto','Septiembre','Octubre','Noviembre','Diciembre'];
  diasFull = ['Domingo','Lunes','Martes','Miércoles','Jueves','Viernes','Sábado'];

  hoy = this.soloFecha(new Date());
  anio = signal(this.hoy.getFullYear());
  mes  = signal(this.hoy.getMonth());

  countPorDia   = signal<Record<string, number>>({});
  ocupadosDia   = signal<string[]>([]);
  fechaSel      = signal<Date | null>(null);
  horaSel       = signal<string | null>(null);
  cargandoSlots = signal(false);

  constructor() { this.cargarMes(); }

  mesLabel = computed(() => `${this.meses[this.mes()]} ${this.anio()}`);

  celdas = computed<Celda[]>(() => {
    const y = this.anio(), m = this.mes(), counts = this.countPorDia();
    const lead = (new Date(y, m, 1).getDay() + 6) % 7;
    const dim  = new Date(y, m + 1, 0).getDate();
    const arr: Celda[] = [];
    for (let i = 0; i < lead; i++) arr.push({ day: null, date: null, ocupados: 0 });
    for (let d = 1; d <= dim; d++) {
      const date = new Date(y, m, d);
      arr.push({ day: d, date, ocupados: counts[this.fmt(date)] ?? 0 });
    }
    while (arr.length % 7 !== 0) arr.push({ day: null, date: null, ocupados: 0 });
    return arr;
  });

  slots = computed<Slot[]>(() => {
    const occ = this.ocupadosDia();
    return HORAS.map(h => ({ hora: h, fin: this.masUnaHora(h), ocupado: occ.includes(h) }));
  });

  fechaLabel     = computed(() => {
    const f = this.fechaSel();
    return f
      ? `${this.diasFull[f.getDay()]} ${f.getDate()} de ${this.meses[f.getMonth()].toLowerCase()}`
      : 'Selecciona un día';
  });
  libreLabel     = computed(() => `${this.slots().filter(s => !s.ocupado).length} de 6 horarios disponibles`);
  puedeConfirmar = computed(() => !!this.fechaSel() && !!this.horaSel());
  resumen        = computed(() => {
    const f = this.fechaSel(), h = this.horaSel();
    if (!f || !h) return 'Selecciona un horario disponible';
    return `${this.diasFull[f.getDay()].slice(0,3)} ${f.getDate()} ${this.meses[f.getMonth()].slice(0,3).toLowerCase()} · ${h}–${this.masUnaHora(h)}`;
  });

  cargarMes(): void {
    this.api.getMes(this.anio(), this.mes() + 1).subscribe({
      next: r => this.countPorDia.set(r ?? {}),
      error: () => this.countPorDia.set({}),
    });
  }

  prevMes(): void { this.cambiarMes(-1); }
  nextMes(): void { this.cambiarMes(1); }

  private cambiarMes(delta: number): void {
    let m = this.mes() + delta, y = this.anio();
    if (m < 0)  { m = 11; y--; } else if (m > 11) { m = 0; y++; }
    this.mes.set(m); this.anio.set(y);
    this.fechaSel.set(null); this.horaSel.set(null); this.ocupadosDia.set([]);
    this.cargarMes();
  }

  seleccionarDia(c: Celda): void {
    if (!this.esSeleccionable(c) || !c.date) return;
    this.fechaSel.set(c.date);
    this.horaSel.set(null);
    this.cargandoSlots.set(true);
    this.api.getDia(this.fmt(c.date)).subscribe({
      next: o => { this.ocupadosDia.set(o ?? []); this.cargandoSlots.set(false); },
      error: ()  => { this.ocupadosDia.set([]);   this.cargandoSlots.set(false); },
    });
  }

  seleccionarHora(s: Slot): void { if (!s.ocupado) this.horaSel.set(s.hora); }

  confirmar(): void {
    if (!this.puedeConfirmar()) return;
    const f = this.fechaSel()!, h = this.horaSel()!;
    this.agendado.emit({ fecha: this.fmt(f), horaInicio: h, horaFin: this.masUnaHora(h) });
  }

  esHabil(c: Celda): boolean     { if (!c.date) return false; const d = c.date.getDay(); return d !== 0 && d !== 6; }
  esPasado(c: Celda): boolean    { return !!c.date && this.soloFecha(c.date) < this.hoy; }
  esHoy(c: Celda): boolean       { return !!c.date && +this.soloFecha(c.date) === +this.hoy; }
  libre(c: Celda): number        { return 6 - c.ocupados; }
  esSeleccionable(c: Celda): boolean { return this.esHabil(c) && !this.esPasado(c) && this.libre(c) > 0; }
  esSeleccionado(c: Celda): boolean  {
    const f = this.fechaSel();
    return !!c.date && !!f && +this.soloFecha(c.date) === +this.soloFecha(f);
  }

  claseDia(c: Celda): Record<string, boolean> {
    if (!c.day) return { 'cal-empty': true };
    if (this.esSeleccionado(c)) return { 'cal-selected': true };
    if (!this.esHabil(c) || this.esPasado(c)) return { 'cal-off': true };
    if (this.libre(c) <= 0) return { 'cal-full': true };
    return { 'cal-available': true, 'cal-today': this.esHoy(c) };
  }

  claseSlot(s: Slot): Record<string, boolean> {
    if (s.ocupado) return { 'slot-off': true };
    if (this.horaSel() === s.hora) return { 'slot-selected': true };
    return { 'slot-available': true };
  }

  private fmt(d: Date): string { return `${d.getFullYear()}-${this.p(d.getMonth() + 1)}-${this.p(d.getDate())}`; }
  private p(n: number): string { return n < 10 ? '0' + n : '' + n; }
  private soloFecha(d: Date): Date { return new Date(d.getFullYear(), d.getMonth(), d.getDate()); }
  private masUnaHora(h: string): string { return this.p(parseInt(h, 10) + 1) + ':00'; }
}
