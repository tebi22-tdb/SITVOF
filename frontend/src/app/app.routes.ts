import { Routes } from '@angular/router';
import {
  academicoGuard,
  coordinadorGuard,
  egresadoGuard,
  loginGuard,
  noApoyoTitulacionGuard,
  serviciosEscolaresGuard,
  staffAdminGuard,
} from './guards/auth.guard';

/**
 * Enrutado SITVO (titulación / residencia profesional y otras modalidades).
 *
 * - Coordinador: inicio → alta de egresados (`/home/alta`) y seguimiento de proceso (`/home/seguimiento-proceso`).
 * - Académico: departamento y revisión de expedientes no residencia.
 * - Egresado: seguimiento de su propio trámite.
 */
export const routes: Routes = [
  { path: '', redirectTo: 'login', pathMatch: 'full' },
  {
    path: 'login',
    loadComponent: () => import('./pages/login/login.component').then((m) => m.LoginComponent),
    canActivate: [loginGuard],
  },
  {
    path: 'home',
    loadComponent: () =>
      import('./pages/coordinador-inicio/coordinador-inicio.component').then((m) => m.CoordinadorInicioComponent),
    canActivate: [coordinadorGuard],
  },
  {
    path: 'home/alta',
    loadComponent: () => import('./pages/home/home.component').then((m) => m.HomeComponent),
    canActivate: [coordinadorGuard],
  },
  {
    path: 'home/alta-docentes',
    loadComponent: () =>
      import('./pages/alta-docentes/alta-docentes.component').then((m) => m.AltaDocentesComponent),
    canActivate: [coordinadorGuard, noApoyoTitulacionGuard],
  },
  {
    path: 'home/catalogos',
    loadComponent: () =>
      import('./pages/catalogos/catalogos.component').then((m) => m.CatalogosComponent),
    canActivate: [coordinadorGuard, noApoyoTitulacionGuard],
  },
  {
    path: 'home/seguimiento-proceso',
    loadComponent: () =>
      import('./pages/seguimiento-proceso/seguimiento-proceso.component').then((m) => m.SeguimientoProcesoComponent),
    canActivate: [coordinadorGuard],
  },
  {
    path: 'home/revisiones/revision/:id',
    loadComponent: () =>
      import('./pages/departamento-academico/revision-documento/revision-documento.component').then(
        (m) => m.RevisionDocumentoComponent,
      ),
    canActivate: [coordinadorGuard, noApoyoTitulacionGuard],
  },
  {
    path: 'home/departamento-academico',
    loadComponent: () =>
      import('./pages/departamento-academico-menu/departamento-academico-menu.component').then(
        (m) => m.DepartamentoAcademicoMenuComponent,
      ),
    canActivate: [coordinadorGuard, noApoyoTitulacionGuard],
  },
  {
    path: 'home/revisiones',
    loadComponent: () =>
      import('./pages/departamento-academico/departamento-academico.component').then((m) => m.DepartamentoAcademicoComponent),
    canActivate: [coordinadorGuard, noApoyoTitulacionGuard],
  },
  {
    path: 'seguimiento',
    loadComponent: () =>
      import('./pages/seguimiento/seguimiento.component').then((m) => m.SeguimientoComponent),
    canActivate: [egresadoGuard],
  },
  {
    path: 'departamento-academico/revision/:id',
    loadComponent: () =>
      import('./pages/departamento-academico/revision-documento/revision-documento.component').then((m) => m.RevisionDocumentoComponent),
    canActivate: [academicoGuard],
  },
  {
    path: 'departamento-academico',
    loadComponent: () =>
      import('./pages/departamento-academico/departamento-academico.component').then((m) => m.DepartamentoAcademicoComponent),
    canActivate: [academicoGuard],
  },
  {
    path: 'home/gestion-cuentas',
    loadComponent: () =>
      import('./pages/gestion-cuentas/gestion-cuentas.component').then((m) => m.GestionCuentasComponent),
    canActivate: [staffAdminGuard],
  },
  {
    path: 'repositorio',
    loadComponent: () =>
      import('./pages/repositorio/repositorio.component').then((m) => m.RepositorioComponent),
  },
  {
    path: 'verificar/:uuid',
    loadComponent: () =>
      import('./pages/verificar-documento/verificar-documento.component').then((m) => m.VerificarDocumentoComponent),
  },
  {
    path: 'servicios-escolares',
    loadComponent: () =>
      import('./pages/servicios-escolares/servicios-escolares.component').then(
        (m) => m.ServiciosEscolaresComponent,
      ),
    canActivate: [serviciosEscolaresGuard],
  },
  {
    path: 'home/config-institucional',
    loadComponent: () =>
      import('./pages/config-institucional/config-institucional.component').then(
        (m) => m.ConfigInstitucionalComponent,
      ),
    canActivate: [coordinadorGuard, noApoyoTitulacionGuard],
  },
  { path: '**', redirectTo: 'login' },
];
