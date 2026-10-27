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
        path: 'chat',
        title: 'Chat',
        data: { nav: { label: 'Chat', icon: 'chat' } },
        loadComponent: () => import('./features/chat/chat').then((m) => m.Chat),
      },
      {
        path: '**',
        title: 'Página no encontrada',
        loadComponent: () => import('./features/not-found/not-found').then((m) => m.NotFound),
      },
    ],
  },
];
