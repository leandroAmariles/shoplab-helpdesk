import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ChatSource } from '../chat-message';
import { SourceList } from './source-list';

const SOURCES: ChatSource[] = [
  { number: 1, title: 'Política de devoluciones', section: 'Plazos', text: 'Tienes 30 días.' },
  { number: 3, title: 'Envíos', section: '', text: 'Cinco días.' },
];

describe('SourceList', () => {
  let fixture: ComponentFixture<SourceList>;

  async function render(sources: ChatSource[], label = 'Fuentes'): Promise<HTMLElement> {
    fixture = TestBed.createComponent(SourceList);
    fixture.componentRef.setInput('sources', sources);
    fixture.componentRef.setInput('label', label);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  it('lists every source with its number, title and section', async () => {
    const dom = await render(SOURCES);

    const entries = [...dom.querySelectorAll('button.source')].map((button) =>
      button.textContent?.replace(/\s+/g, ' ').trim(),
    );
    expect(entries).toEqual(['1. Política de devoluciones › Plazos', '3. Envíos']);
  });

  it('names the region and each entry for screen readers', async () => {
    const dom = await render(SOURCES);

    expect(dom.querySelector('section')?.getAttribute('aria-label')).toBe('Fuentes');
    expect(dom.querySelector('h3')?.textContent).toBe('Fuentes');
    expect(dom.querySelector('button.source')?.getAttribute('aria-label')).toBe(
      'Fuente 1: Política de devoluciones',
    );
  });

  it('shows the label it is given', async () => {
    const dom = await render(SOURCES, 'Fuentes consultadas');

    expect(dom.querySelector('h3')?.textContent).toBe('Fuentes consultadas');
  });

  it('renders nothing without sources', async () => {
    const dom = await render([]);

    expect(dom.querySelector('section')).toBeNull();
    expect(dom.querySelector('button')).toBeNull();
  });

  it('emits the source of the entry that is activated', async () => {
    const dom = await render(SOURCES);
    const opened: ChatSource[] = [];
    fixture.componentInstance.open.subscribe((source) => opened.push(source));

    dom.querySelectorAll<HTMLButtonElement>('button.source')[1].click();

    expect(opened).toEqual([SOURCES[1]]);
  });

  it('never interprets the title as HTML', async () => {
    const dom = await render([{ number: 1, title: '<img src=x onerror=alert(1)>', section: '', text: 'x' }]);

    expect(dom.querySelector('img')).toBeNull();
    expect(dom.querySelector('button.source')?.textContent).toContain('<img src=x onerror=alert(1)>');
  });
});
