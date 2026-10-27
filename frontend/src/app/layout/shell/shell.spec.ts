import { BreakpointObserver, BreakpointState } from '@angular/cdk/layout';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatSidenav } from '@angular/material/sidenav';
import { By } from '@angular/platform-browser';
import { provideRouter } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { Shell } from './shell';

@Component({ template: '' })
class Dummy {}

describe('Shell', () => {
  const breakpoint$ = new BehaviorSubject<BreakpointState>({ matches: false, breakpoints: {} });

  beforeEach(async () => {
    breakpoint$.next({ matches: false, breakpoints: {} });
    await TestBed.configureTestingModule({
      imports: [Shell],
      providers: [
        { provide: BreakpointObserver, useValue: { observe: () => breakpoint$ } },
        provideRouter([
          {
            path: '',
            component: Shell,
            children: [
              { path: '', component: Dummy, data: { nav: { label: 'Inicio', icon: 'home' } } },
              { path: '**', component: Dummy },
            ],
          },
        ]),
      ],
    }).compileComponents();
  });

  function sidenav(fixture: ReturnType<typeof TestBed.createComponent<Shell>>): MatSidenav {
    return fixture.debugElement.query(By.directive(MatSidenav)).componentInstance;
  }

  it('keeps the navigation open and beside the content on wide screens', async () => {
    const fixture = TestBed.createComponent(Shell);
    await fixture.whenStable();

    expect(sidenav(fixture).mode).toBe('side');
    expect(sidenav(fixture).opened).toBe(true);
  });

  it('closes the navigation and overlays it on handset screens', async () => {
    breakpoint$.next({ matches: true, breakpoints: {} });
    const fixture = TestBed.createComponent(Shell);
    await fixture.whenStable();

    expect(sidenav(fixture).mode).toBe('over');
    expect(sidenav(fixture).opened).toBe(false);
  });

  it('closes the navigation after navigating only on handset screens', async () => {
    const fixture = TestBed.createComponent(Shell);
    await fixture.whenStable();
    fixture.componentInstance.onNavigate();
    expect(fixture.componentInstance.sidenavOpen()).toBe(true);

    breakpoint$.next({ matches: true, breakpoints: {} });
    fixture.componentInstance.sidenavOpen.set(true);
    fixture.componentInstance.onNavigate();
    expect(fixture.componentInstance.sidenavOpen()).toBe(false);
  });

  it('lists the routes that declare navigation data', async () => {
    const fixture = TestBed.createComponent(Shell);
    await fixture.whenStable();

    expect(fixture.componentInstance.navItems()).toEqual([
      { label: 'Inicio', icon: 'home', link: '/' },
    ]);
  });
});
