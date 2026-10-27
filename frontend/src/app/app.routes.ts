import { Routes } from '@angular/router';
import { Shell } from './layout/shell/shell';

export const routes: Routes = [
  {
    path: '',
    component: Shell,
    children: [
      {
        path: '',
        pathMatch: 'full',
        title: 'Inicio',
        data: { nav: { label: 'Inicio', icon: 'home' } },
        loadComponent: () => import('./features/home/home').then((m) => m.Home),
      },
      {
        path: '**',
        title: 'Página no encontrada',
        loadComponent: () => import('./features/not-found/not-found').then((m) => m.NotFound),
      },
    ],
  },
];
