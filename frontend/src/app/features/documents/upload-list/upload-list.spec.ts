import { ComponentFixture, TestBed } from '@angular/core/testing';
import { UploadItem, UploadState } from '../document';
import { UploadList } from './upload-list';

function item(state: UploadState, extra: Partial<UploadItem> = {}): UploadItem {
  return {
    id: `upload-${state}`,
    file: new File(['x'], `${state}.md`),
    name: `${state}.md`,
    size: 2048,
    state,
    percent: 0,
    ...extra,
  };
}

describe('UploadList', () => {
  let fixture: ComponentFixture<UploadList>;
  let dom: HTMLElement;

  async function render(uploads: UploadItem[]) {
    fixture = TestBed.createComponent(UploadList);
    fixture.componentRef.setInput('uploads', uploads);
    await fixture.whenStable();
    dom = fixture.nativeElement as HTMLElement;
  }

  const status = () => dom.querySelector('[role="status"]')?.textContent?.trim();
  const button = (text: string) =>
    [...dom.querySelectorAll('button')].find((b) => b.textContent?.trim() === text);

  it('renders nothing without uploads', async () => {
    await render([]);

    expect(dom.querySelector('section')).toBeNull();
  });

  it('shows the progress of the upload with an accessible progress bar', async () => {
    await render([item('sending', { percent: 42 })]);

    const bar = dom.querySelector('[role="progressbar"]') as HTMLElement;
    expect(bar).not.toBeNull();
    expect(bar.getAttribute('aria-valuenow')).toBe('42');
    expect(bar.getAttribute('aria-label')).toBe('Progreso de envío de sending.md');
    expect(status()).toBe('Enviando… 42 %');
  });

  it('says it is processing once everything was sent', async () => {
    await render([item('processing', { percent: 100 })]);

    expect(dom.querySelector('[role="progressbar"]')).not.toBeNull();
    expect(status()).toContain('Procesando');
  });

  it('shows the chunks of a stored document and whether it replaced another', async () => {
    await render([item('done', { chunkCount: 14, replaced: false })]);
    expect(status()).toBe('Subido: 14 chunks. Pendiente de indexar.');

    await render([item('done', { chunkCount: 9, replaced: true })]);
    expect(status()).toBe('Actualizado: 9 chunks. Pendiente de indexar.');
    expect(dom.querySelector('[role="progressbar"]')).toBeNull();
  });

  it('shows the cause of a rejection and offers no retry', async () => {
    await render([item('rejected', { message: 'Formato no admitido' })]);

    expect(status()).toBe('Formato no admitido');
    expect(button('Reintentar')).toBeUndefined();
    expect(button('Quitar')).toBeDefined();
  });

  it('offers to retry a failed upload and emits its id', async () => {
    await render([item('failed', { message: 'No disponible' })]);
    const retried = vi.fn();
    fixture.componentInstance.retryUpload.subscribe(retried);

    button('Reintentar')?.click();

    expect(retried).toHaveBeenCalledWith('upload-failed');
  });

  it('offers to cancel an upload in progress, with a label naming the file', async () => {
    await render([item('sending', { percent: 10 })]);
    const cancelled = vi.fn();
    fixture.componentInstance.cancelUpload.subscribe(cancelled);

    const cancel = button('Cancelar');
    expect(cancel?.getAttribute('aria-label')).toBe('Cancelar la subida de sending.md');
    cancel?.click();

    expect(cancelled).toHaveBeenCalledWith('upload-sending');
    expect(button('Quitar')).toBeUndefined();
  });

  it('a cancelled upload can be retried or dismissed', async () => {
    await render([item('cancelled')]);
    const dismissed = vi.fn();
    fixture.componentInstance.dismissUpload.subscribe(dismissed);

    expect(status()).toBe('Subida cancelada');
    expect(button('Reintentar')).toBeDefined();
    button('Quitar')?.click();

    expect(dismissed).toHaveBeenCalledWith('upload-cancelled');
  });

  it('shows a queued upload and its size', async () => {
    await render([item('queued')]);

    expect(status()).toBe('En cola');
    expect(dom.textContent).toContain('2 KB');
  });
});
