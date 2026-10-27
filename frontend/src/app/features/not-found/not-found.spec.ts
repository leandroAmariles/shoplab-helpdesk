import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { NotFound } from './not-found';

describe('NotFound', () => {
  it('shows the message and links back to the home page', async () => {
    await TestBed.configureTestingModule({
      imports: [NotFound],
      providers: [provideRouter([])],
    }).compileComponents();
    const fixture = TestBed.createComponent(NotFound);
    await fixture.whenStable();

    const element = fixture.nativeElement as HTMLElement;
    expect(element.textContent).toContain('Página no encontrada');
    expect(element.querySelector('a')?.getAttribute('href')).toBe('/');
  });
});
