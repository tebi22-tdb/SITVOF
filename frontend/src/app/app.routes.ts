import { Routes } from '@angular/router';
import {
  academicoGuard,
  coordinadorGuard,
  egresadoGuard,
  loginGuard,
  noApoyoTitulacionGuard,
  noApoyoTitulacionPeroDepGuard,
  noDepGuard,
  reportesGuard,
  serviciosEscolaresGuard,
  staffAdminGuard,
} from './guards/auth.guard';

/**
 * Enrutado SITVO (titulación / residencia profesional y otras modalidades).
 *
 * - Administrador (pleno): dashboard (`/home`) con acceso a todos los módulos.
 * - DEP (División de Estudios Profesionales): aterriza directo en
 *   `/home/seguimiento-proceso` — solo esa pantalla y `/home/alta` (Inicio
 *   del proceso), sin dashboard ni el resto de módulos (`noApoyoTitulacionPeroDepGuard`
 *   en esas dos rutas, `noApoyoTitulacionGuard` en el resto de /home/**).
 * - Apoyo a titulación: aterriza directo en `/home/revisiones` — solo revisa
 *   expedientes, sin dashboard ni acceso a alta/seguimiento.
 * - Subdirección académica: solo Reportes y analítica (`/home/reportes`).
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
    path: 'restablecer-password',
    loadComponent: () =>
      import('./pages/restablecer-password/restablecer-password.component').then(
        (m) => m.RestablecerPasswordComponent,
      ),
  },
  {
    path: 'home',
    loadComponent: () =>
      import('./pages/coordinador-inicio/coordinador-inicio.component').then((m) => m.CoordinadorInicioComponent),
    canActivate: [coordinadorGuard, noApoyoTitulacionGuard],
  },
  {
    path: 'home/alta',
    loadComponent: () => import('./pages/home/home.component').then((m) => m.HomeComponent),
    canActivate: [coordinadorGuard, noApoyoTitulacionPeroDepGuard],
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
    path: 'home/reportes',
    loadComponent: () =>
      import('./pages/reportes/reportes.component').then((m) => m.ReportesComponent),
    canActivate: [reportesGuard],
  },
  {
    path: 'home/seguimiento-proceso',
    loadComponent: () =>
      import('./pages/seguimiento-proceso/seguimiento-proceso.component').then((m) => m.SeguimientoProcesoComponent),
    canActivate: [coordinadorGuard, noApoyoTitulacionPeroDepGuard],
  },
  {
    // Sin noApoyoTitulacionGuard a propósito: es la única pantalla de staff completo
    // a la que apoyo_titulacion tiene acceso. noDepGuard sí aplica: DEP no entra aquí.
    path: 'home/revisiones/revision/:id',
    loadComponent: () =>
      import('./pages/departamento-academico/revision-documento/revision-documento.component').then(
        (m) => m.RevisionDocumentoComponent,
      ),
    canActivate: [coordinadorGuard, noDepGuard],
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
    // Sin noApoyoTitulacionGuard a propósito: es la pantalla a la que apoyo_titulacion
    // aterriza directo tras el login. noDepGuard sí aplica: DEP no entra aquí.
    path: 'home/revisiones',
    loadComponent: () =>
      import('./pages/departamento-academico/departamento-academico.component').then((m) => m.DepartamentoAcademicoComponent),
    canActivate: [coordinadorGuard, noDepGuard],
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
    path: 'home/agregar-usuario',
    loadComponent: () =>
      import('./pages/agregar-usuario/agregar-usuario.component').then((m) => m.AgregarUsuarioComponent),
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
