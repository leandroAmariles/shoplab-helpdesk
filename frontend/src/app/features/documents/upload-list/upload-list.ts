import { Component, input, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { UploadItem } from '../document';
import { formatSize } from '../upload-validation';

/** Uploads of this visit, with progress, cause of each failure and the actions that apply to each state. */
@Component({
  selector: 'app-upload-list',
  imports: [MatButtonModule, MatIconModule, MatProgressBarModule],
  templateUrl: './upload-list.html',
  styleUrl: './upload-list.scss',
})
export class UploadList {
  readonly uploads = input.required<UploadItem[]>();

  readonly cancelUpload = output<string>();
  readonly retryUpload = output<string>();
  readonly dismissUpload = output<string>();

  protected readonly formatSize = formatSize;

  protected statusText(upload: UploadItem): string {
    switch (upload.state) {
      case 'queued':
        return 'En cola';
      case 'sending':
        return `Enviando… ${upload.percent} %`;
      case 'processing':
        return 'Procesando… el servidor está troceando el documento';
      case 'done':
        return `${upload.replaced ? 'Actualizado' : 'Subido'}: ${upload.chunkCount ?? 0} chunks. Pendiente de indexar.`;
      case 'cancelled':
        return 'Subida cancelada';
      default:
        return upload.message ?? 'No se pudo subir el documento.';
    }
  }

  protected canCancel(upload: UploadItem): boolean {
    return upload.state === 'queued' || upload.state === 'sending' || upload.state === 'processing';
  }

  protected canRetry(upload: UploadItem): boolean {
    return upload.state === 'failed' || upload.state === 'cancelled';
  }

  protected icon(upload: UploadItem): string {
    switch (upload.state) {
      case 'done':
        return 'check_circle';
      case 'failed':
      case 'rejected':
        return 'error';
      case 'cancelled':
        return 'block';
      default:
        return 'upload';
    }
  }
}
