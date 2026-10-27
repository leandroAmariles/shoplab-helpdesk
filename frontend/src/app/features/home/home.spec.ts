import { TestBed } from '@angular/core/testing';
import { Home } from './home';

describe('Home', () => {
  it('shows the application title', async () => {
    await TestBed.configureTestingModule({ imports: [Home] }).compileComponents();
    const fixture = TestBed.createComponent(Home);
    await fixture.whenStable();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('ShopLab Helpdesk');
  });
});
