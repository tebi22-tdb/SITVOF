import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { map } from 'rxjs/operators';
import { environment } from '../../environments/environment';

const API = `${environment.apiUrl}/api/egresados`;

@Injectable({ providedIn: 'root' })
export class ActoService {
  private http = inject(HttpClient);

  private fetchOcupados(): Observable<Date[]> {
    return this.http
      .get<{ ocupados: string[] }>(`${API}/agenda-acto-9-3/ocupados`)
      .pipe(
        map(r => (r.ocupados ?? []).map(s => new Date(s)).filter(d => !isNaN(d.getTime()))),
      );
  }

  /** Devuelve { 'YYYY-MM-DD': nº de actos ocupados } para ese mes. */
  getMes(anio: number, mes: number): Observable<Record<string, number>> {
    return this.fetchOcupados().pipe(
      map(fechas => {
        const result: Record<string, number> = {};
        fechas
          .filter(d => d.getFullYear() === anio && d.getMonth() + 1 === mes)
          .forEach(d => {
            const key = this.fmtDate(d);
            result[key] = (result[key] ?? 0) + 1;
          });
        return result;
      }),
    );
  }

  /** Devuelve las horas ocupadas de un día: ['09:00', '11:00', …] */
  getDia(fecha: string): Observable<string[]> {
    return this.fetchOcupados().pipe(
      map(fechas =>
        fechas
          .filter(d => this.fmtDate(d) === fecha)
          .map(d => this.pad(d.getHours()) + ':00'),
      ),
    );
  }

  private fmtDate(d: Date): string {
    return `${d.getFullYear()}-${this.pad(d.getMonth() + 1)}-${this.pad(d.getDate())}`;
  }

  private pad(n: number): string {
    return n < 10 ? '0' + n : '' + n;
  }
}
