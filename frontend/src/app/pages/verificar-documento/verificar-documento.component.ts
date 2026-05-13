import { Component, OnInit } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { CommonModule, DatePipe } from '@angular/common';

interface VerificacionResultado {
  valido: boolean;
  nombre?: string;
  numero_control?: string;
  titulo_proyecto?: string;
  modalidad?: string;
  carrera?: string;
  institucion?: string;
  fecha_certificacion?: string;
  estatus?: string;
  mensaje?: string;
}

@Component({
  selector: 'app-verificar-documento',
  standalone: true,
  imports: [CommonModule, DatePipe],
  templateUrl: './verificar-documento.component.html',
  styleUrl: './verificar-documento.component.css',
})
export class VerificarDocumentoComponent implements OnInit {
  uuid = '';
  resultado: VerificacionResultado | null = null;
  cargando = true;
  error = '';

  constructor(private route: ActivatedRoute, private http: HttpClient) {}

  ngOnInit() {
    this.uuid = this.route.snapshot.paramMap.get('uuid') ?? '';
    if (!this.uuid) {
      this.error = 'ID de verificacion no especificado.';
      this.cargando = false;
      return;
    }
    this.http.get<VerificacionResultado>(`/api/verificar/${this.uuid}`).subscribe({
      next: (res) => {
        this.resultado = res;
        this.cargando = false;
      },
      error: () => {
        this.error = 'No se pudo conectar al sistema. Intente de nuevo.';
        this.cargando = false;
      },
    });
  }
}
