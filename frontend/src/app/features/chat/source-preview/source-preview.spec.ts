import { TestBed } from '@angular/core/testing';
import { MatDialog, MatDialogRef } from '@angular/material/dialog';
import { firstValueFrom } from 'rxjs';
import { ChatSource } from '../chat-message';
import { SourcePreview } from './source-preview';

const SOURCE: ChatSource = {
  number: 2,
  title: 'Política de devoluciones',
  section: 'Plazos > Productos usados',
  text: 'Tienes **30 días** para devolver.\n\n- Producto sin uso\n- Con factura',
};

describe('SourcePreview', () => {
  let dialog: MatDialog;
  let ref: MatDialogRef<SourcePreview>;

  async function open(source: ChatSource): Promise<HTMLElement> {
    ref = dialog.open(SourcePreview, { data: source });
    await firstValueFrom(ref.afterOpened());
    return document.querySelector('mat-dialog-container') as HTMLElement;
  }

  beforeEach(() => {
    dialog = TestBed.inject(MatDialog);
  });

  afterEach(() => {
    dialog.closeAll();
  });

  it('shows the title, the section and the whole text of the fragment', async () => {
    const container = await open(SOURCE);

    expect(container.querySelector('h2')?.textContent).toContain('Política de devoluciones');
    expect(container.textContent).toContain('Plazos > Productos usados');
    expect(container.querySelector('strong')?.textContent).toBe('30 días');
    expect(container.querySelectorAll('li')).toHaveLength(2);
  });

  it('hides the section when there is none', async () => {
    const container = await open({ ...SOURCE, section: '' });

    expect(container.querySelector('.section')).toBeNull();
  });

  it('is a dialog named by the title', async () => {
    const container = await open(SOURCE);

    expect(container.getAttribute('role')).toBe('dialog');
    const labelledBy = container.getAttribute('aria-labelledby');
    expect(labelledBy).toBeTruthy();
    expect(document.getElementById(labelledBy as string)?.textContent).toContain('Política de devoluciones');
  });

  it('treats the fragment as untrusted: nothing active is inserted or loaded', async () => {
    const container = await open({
      ...SOURCE,
      text: 'hola <script>alert(1)</script><img src=x onerror=alert(1)> ![logo](https://example.com/a.png) [x](javascript:alert(1))',
    });

    expect(container.querySelector('script')).toBeNull();
    expect(container.querySelector('img')).toBeNull();
    expect(container.innerHTML).not.toContain('onerror');
    expect(container.querySelector('a[href^="javascript"]')).toBeNull();
  });

  it('does not turn a citation-looking marker of the fragment into a button', async () => {
    const container = await open({ ...SOURCE, text: 'Ver también [1] y [2].' });

    expect(container.querySelector('.markdown button')).toBeNull();
    expect(container.textContent).toContain('[1]');
  });

  it('shows the title as text, never as HTML', async () => {
    const container = await open({ ...SOURCE, title: '<img src=x onerror=alert(1)>' });

    expect(container.querySelector('img')).toBeNull();
    expect(container.querySelector('h2')?.textContent).toContain('<img src=x onerror=alert(1)>');
  });

  it('closes with the Cerrar button', async () => {
    const container = await open(SOURCE);
    const closed = firstValueFrom(ref.afterClosed());

    const button = [...container.querySelectorAll('button')].find((b) => b.textContent?.includes('Cerrar'));
    button?.click();
    await closed;

    expect(dialog.openDialogs).toHaveLength(0);
  });
});
