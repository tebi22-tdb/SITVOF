import { Component, OnInit } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { CommonModule, DatePipe } from '@angular/common';
import { LOGO_ITVO, LOGO_TECNM } from '../../core/logos';
import { textoErrorUsuario } from '../../core/mensaje-conexion';

interface VerificacionResultado {
  valido: boolean;
  nombre?: string;
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
  readonly logoTecnm = LOGO_TECNM;
  readonly logoItvo = LOGO_ITVO;
  uuid = '';
  resultado: VerificacionResultado | null = null;
  cargando = true;
  error = '';

  constructor(private route: ActivatedRoute, private http: HttpClient) {}

  ngOnInit() {
    this.uuid = this.route.snapshot.paramMap.get('uuid') ?? '';
    if (!this.uuid) {
      this.error = 'No se encontró el documento para comprobar.';
      this.cargando = false;
      return;
    }
    this.http.get<VerificacionResultado>(`/api/verificar/${this.uuid}`).subscribe({
      next: (res) => {
        this.resultado = res;
        this.cargando = false;
      },
      error: (err) => {
        this.error = textoErrorUsuario(err, 'No se pudo comprobar el documento. Intenta de nuevo.');
        this.cargando = false;
      },
    });
  }
}
