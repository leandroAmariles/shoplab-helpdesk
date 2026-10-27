import { BreakpointObserver, Breakpoints } from '@angular/cdk/layout';
import { Component, computed, inject, linkedSignal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatListModule } from '@angular/material/list';
import { MatSidenavModule } from '@angular/material/sidenav';
import { MatToolbarModule } from '@angular/material/toolbar';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { map } from 'rxjs';
import { NavItem } from './nav-item';

@Component({
  selector: 'app-shell',
  imports: [
    RouterOutlet,
    RouterLink,
    RouterLinkActive,
    MatToolbarModule,
    MatSidenavModule,
    MatListModule,
    MatIconModule,
    MatButtonModule,
  ],
  templateUrl: './shell.html',
  styleUrl: './shell.scss',
})
export class Shell {
  private readonly router = inject(Router);

  protected readonly isHandset = toSignal(
    inject(BreakpointObserver)
      .observe(Breakpoints.Handset)
      .pipe(map((state) => state.matches)),
    { initialValue: false },
  );

  /** Open on wide screens, closed on handsets; the user can still toggle it. */
  readonly sidenavOpen = linkedSignal(() => !this.isHandset());

  /** The navigation is derived from the route config: one source of truth. */
  readonly navItems = computed<NavItem[]>(() =>
    this.router.config
      .flatMap((route) => route.children ?? [])
      .filter((route) => route.data?.['nav'])
      .map((route) => ({ ...route.data?.['nav'], link: `/${route.path ?? ''}` })),
  );

  onNavigate(): void {
    if (this.isHandset()) {
      this.sidenavOpen.set(false);
    }
  }
}
