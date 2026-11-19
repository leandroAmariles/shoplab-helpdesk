import { TestBed } from '@angular/core/testing';
import { DocumentSummary } from '../document';
import { DocumentTable } from './document-table';

const DOCUMENTS: DocumentSummary[] = [
  {
    id: '1',
    name: 'politica-devoluciones.md',
    title: 'Política de devoluciones',
    status: 'INDEXED',
    chunkCount: 14,
    error: null,
    updatedAt: '2026-11-10T15:02:11.482Z',
    indexedAt: '2026-11-10T15:02:19.003Z',
  },
  {
    id: '2',
    name: 'envios.pdf',
    title: 'envios.pdf',
    status: 'ERROR',
    chunkCount: 9,
    error: 'The embedding model is not available',
    updatedAt: '2026-11-10T14:40:00.100Z',
    indexedAt: null,
  },
  {
    id: '3',
    name: 'nuevo.md',
    title: 'Nuevo',
    status: 'PENDING',
    chunkCount: 0,
    error: null,
    updatedAt: '2026-11-10T14:00:00Z',
    indexedAt: null,
  },
];

describe('DocumentTable', () => {
  async function render(documents: DocumentSummary[]): Promise<HTMLElement> {
    const fixture = TestBed.createComponent(DocumentTable);
    fixture.componentRef.setInput('documents', documents);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  const rows = (dom: HTMLElement) => [...dom.querySelectorAll('tbody tr')];
  const cells = (row: Element) => [...row.querySelectorAll('td')].map((c) => c.textContent?.trim());

  it('has headers and an accessible name', async () => {
    const dom = await render(DOCUMENTS);

    expect([...dom.querySelectorAll('th[scope="col"]')].map((th) => th.textContent?.trim())).toEqual([
      'Documento',
      'Estado',
      'Chunks',
      'Actualizado',
    ]);
    expect(dom.querySelector('caption')?.textContent).toContain('Documentos de la base de conocimiento');
  });

  it('shows one row per document in the order received, with state as text and chunks', async () => {
    const dom = await render(DOCUMENTS);

    expect(rows(dom)).toHaveLength(3);
    expect(cells(rows(dom)[0])[0]).toContain('politica-devoluciones.md');
    expect(cells(rows(dom)[0])[0]).toContain('Política de devoluciones');
    expect(cells(rows(dom)[0])[1]).toContain('Indexado');
    expect(cells(rows(dom)[0])[2]).toBe('14');
    expect(cells(rows(dom)[2])[1]).toContain('Pendiente');
    expect(cells(rows(dom)[2])[2]).toBe('0');
  });

  it('shows the cause of an error in Spanish, never the stored text', async () => {
    const dom = await render(DOCUMENTS);

    const state = cells(rows(dom)[1])[1];
    expect(state).toContain('Error');
    expect(state).toContain('El modelo de embeddings no estaba disponible');
    expect(dom.textContent).not.toContain('The embedding model');
  });

  it('does not repeat the title when it is the file name', async () => {
    const dom = await render(DOCUMENTS);

    expect(rows(dom)[1].querySelector('.title')).toBeNull();
  });

  it('tells the state with an icon hidden from screen readers next to the text', async () => {
    const dom = await render(DOCUMENTS);

    const icon = rows(dom)[0].querySelector('.status mat-icon');
    expect(icon?.getAttribute('aria-hidden')).toBe('true');
    expect(icon?.textContent?.trim()).toBe('check_circle');
  });

  it('gives each cell a label for the narrow layout and a machine-readable date', async () => {
    const dom = await render(DOCUMENTS);

    expect([...rows(dom)[0].querySelectorAll('td')].map((td) => td.dataset['label'])).toEqual([
      'Documento',
      'Estado',
      'Chunks',
      'Actualizado',
    ]);
    expect(rows(dom)[0].querySelector('time')?.getAttribute('datetime')).toBe(
      '2026-11-10T15:02:11.482Z',
    );
  });
});
