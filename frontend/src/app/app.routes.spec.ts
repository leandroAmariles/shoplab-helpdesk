import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { Home } from './features/home/home';
import { NotFound } from './features/not-found/not-found';
import { Shell } from './layout/shell/shell';
import { routes } from './app.routes';

describe('routes', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideRouter(routes)] });
  });

  it('renders Home inside the shell at the root', async () => {
    const harness = await RouterTestingHarness.create('/');

    expect(harness.fixture.debugElement.query(By.directive(Shell))).not.toBeNull();
    expect(harness.fixture.debugElement.query(By.directive(Home))).not.toBeNull();
    expect(TestBed.inject(Router).url).toBe('/');
  });

  it('renders NotFound inside the shell for an unknown route', async () => {
    const harness = await RouterTestingHarness.create('/no-existe');

    expect(harness.fixture.debugElement.query(By.directive(Shell))).not.toBeNull();
    expect(harness.fixture.debugElement.query(By.directive(NotFound))).not.toBeNull();
  });

  it('declares the wildcard route last', () => {
    const children = routes[0].children ?? [];

    expect(children[children.length - 1].path).toBe('**');
  });

  it('gives every screen a Spanish title', () => {
    const children = routes[0].children ?? [];

    expect(children.every((route) => typeof route.title === 'string')).toBe(true);
  });
});
