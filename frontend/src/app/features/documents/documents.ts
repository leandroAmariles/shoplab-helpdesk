import { Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { DocumentTable } from './document-table/document-table';
import { DocumentsStore } from './documents-store';
import { UploadList } from './upload-list/upload-list';
import { ACCEPT_ATTRIBUTE } from './upload-validation';

/** Administration of the knowledge base: upload PDF and Markdown documents and follow their indexing. */
@Component({
  selector: 'app-documents',
  imports: [MatButtonModule, MatIconModule, UploadList, DocumentTable],
  providers: [DocumentsStore],
  templateUrl: './documents.html',
  styleUrl: './documents.scss',
})
export class Documents {
  protected readonly store = inject(DocumentsStore);
  protected readonly accept = ACCEPT_ATTRIBUTE;
  protected readonly dragging = signal(false);

  constructor() {
    this.store.load();
  }

  protected onFilesChosen(input: HTMLInputElement): void {
    if (input.files) {
      this.store.addFiles(Array.from(input.files));
    }
    // The same file can be chosen again (e.g. after fixing it).
    input.value = '';
  }

  protected onDragOver(event: DragEvent): void {
    event.preventDefault();
    if (event.dataTransfer) {
      event.dataTransfer.dropEffect = 'copy';
    }
    this.dragging.set(true);
  }

  protected onDragLeave(): void {
    this.dragging.set(false);
  }

  protected onDrop(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(false);
    const files = event.dataTransfer?.files;
    if (files && files.length > 0) {
      this.store.addFiles(Array.from(files));
    }
  }
}
