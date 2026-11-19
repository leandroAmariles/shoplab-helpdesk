import { Component, computed, input } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { DocumentSummary } from '../document';
import { indexErrorCause, statusPresentation } from '../document-status';

const DATE_FORMAT = new Intl.DateTimeFormat('es', { dateStyle: 'short', timeStyle: 'short' });

interface DocumentRow {
  id: string;
  name: string;
  title: string | null;
  status: string;
  statusLabel: string;
  statusIcon: string;
  cause: string | null;
  chunkCount: number;
  updatedAt: string;
  updatedAtText: string;
}

/** The knowledge base as `retrieval` reports it: the state is shown, never recomputed (FR-006). */
@Component({
  selector: 'app-document-table',
  imports: [MatIconModule],
  templateUrl: './document-table.html',
  styleUrl: './document-table.scss',
})
export class DocumentTable {
  readonly documents = input.required<DocumentSummary[]>();

  protected readonly rows = computed<DocumentRow[]>(() =>
    this.documents().map((document) => {
      const presentation = statusPresentation(document.status);
      return {
        id: document.id,
        name: document.name,
        title: document.title && document.title !== document.name ? document.title : null,
        status: document.status,
        statusLabel: presentation.label,
        statusIcon: presentation.icon,
        cause: indexErrorCause(document.status, document.error),
        chunkCount: document.chunkCount,
        updatedAt: document.updatedAt,
        updatedAtText: formatDate(document.updatedAt),
      };
    }),
  );
}

function formatDate(iso: string): string {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? iso : DATE_FORMAT.format(date);
}
