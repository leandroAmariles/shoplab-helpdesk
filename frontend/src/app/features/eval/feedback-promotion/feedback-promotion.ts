import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { FeedbackApiClient, FeedbackListItem } from './feedback-api.client';

export type FeedbackFilterMode = 'pending' | 'all' | 'promoted';

export interface GoldenCategoryOption {
  value: string;
  label: string;
}

export const GOLDEN_CATEGORIES: readonly GoldenCategoryOption[] = [
  { value: 'returns', label: 'Devoluciones (returns)' },
  { value: 'shipping', label: 'Envíos (shipping)' },
  { value: 'payments', label: 'Pagos (payments)' },
  { value: 'warranty', label: 'Garantías (warranty)' },
  { value: 'privacy', label: 'Privacidad (privacy)' },
  { value: 'off_topic', label: 'Fuera de tema (off_topic)' },
  { value: 'small_talk', label: 'Cortesía (small_talk)' },
  { value: 'injection', label: 'Inyección de prompt (injection)' },
];

const NO_DOC_CATEGORIES = new Set(['off_topic', 'small_talk', 'injection']);

function inferCategory(documents: string[]): string {
  for (const doc of documents) {
    const prefix = doc.split('-')[0];
    if (['returns', 'shipping', 'payments', 'warranty', 'privacy'].includes(prefix)) {
      return prefix;
    }
  }
  return 'returns';
}

function extractPromotionError(error: unknown): string {
  if (error instanceof HttpErrorResponse) {
    const body: unknown = error.error;
    if (typeof body === 'object' && body !== null) {
      const record = body as Record<string, unknown>;
      if (typeof record['detail'] === 'string' && record['detail'].trim() !== '') {
        return `El caso golden no es válido: ${record['detail']}`;
      }
      if (record['code'] === 'SLHD_CHAT_00013') {
        return 'Esta valoración o el identificador G<NNN> elegido ya fue promovido.';
      }
      if (record['code'] === 'SLHD_CHAT_00015') {
        return 'Solo las respuestas valoradas con 👎 se pueden promover a casos golden.';
      }
    }
  }
  return 'No se pudo promover la valoración a caso golden. Revisa los datos e inténtalo de nuevo.';
}

/**
 * Section in `/admin-eval` to inspect customer answers rated with 👎 and promote them to validated golden cases
 * (`G<NNN>`) with canonical JSONL ready for `eval/golden.jsonl` (LAB-33).
 */
@Component({
  selector: 'app-feedback-promotion',
  imports: [MatButtonModule, MatIconModule],
  templateUrl: './feedback-promotion.html',
  styleUrl: './feedback-promotion.scss',
})
export class FeedbackPromotion {
  private readonly api = inject(FeedbackApiClient);

  protected readonly categories = GOLDEN_CATEGORIES;

  readonly loaded = signal(false);
  readonly loading = signal(false);
  readonly loadError = signal<string | null>(null);
  readonly filterMode = signal<FeedbackFilterMode>('pending');

  readonly items = signal<FeedbackListItem[]>([]);
  readonly pendingCount = signal(0);
  readonly promotedCount = signal(0);
  readonly suggestedNextCaseId = signal('G121');

  readonly activeTurnId = signal<string | null>(null);
  readonly formCaseId = signal('');
  readonly formCategory = signal('returns');
  readonly formQuestion = signal('');
  readonly formReferenceAnswer = signal('');
  readonly formExpectedDocuments = signal('');
  readonly promoting = signal(false);
  readonly promoteError = signal<string | null>(null);
  readonly lastPromotedItem = signal<FeedbackListItem | null>(null);
  readonly copiedTurnId = signal<string | null>(null);

  protected readonly activeItem = computed(() => {
    const id = this.activeTurnId();
    return id ? (this.items().find((item) => item.turnId === id) ?? null) : null;
  });

  load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    const mode = this.filterMode();
    const promoted = mode === 'pending' ? false : mode === 'promoted' ? true : null;
    this.api.listFeedback(promoted).subscribe({
      next: (result) => {
        this.items.set(result.items);
        this.pendingCount.set(result.pendingCount);
        this.promotedCount.set(result.promotedCount);
        this.suggestedNextCaseId.set(result.suggestedNextCaseId);
        this.loaded.set(true);
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
        this.loadError.set('No se pudieron cargar las valoraciones con 👎.');
      },
    });
  }

  onFilterChange(mode: FeedbackFilterMode): void {
    this.filterMode.set(mode);
    if (this.loaded()) {
      this.load();
    }
  }

  openPromotionForm(item: FeedbackListItem): void {
    if (item.promotedCaseId) {
      return;
    }
    const initialDocs = item.deliveredDocuments.slice(0, 2);
    const category = inferCategory(initialDocs);
    this.activeTurnId.set(item.turnId);
    this.formCaseId.set(this.suggestedNextCaseId());
    this.formCategory.set(category);
    this.formQuestion.set(item.question);
    this.formReferenceAnswer.set('');
    this.formExpectedDocuments.set(initialDocs.join(', '));
    this.promoteError.set(null);
  }

  cancelPromotionForm(): void {
    this.activeTurnId.set(null);
    this.promoteError.set(null);
  }

  onCategoryChange(category: string): void {
    this.formCategory.set(category);
    if (NO_DOC_CATEGORIES.has(category)) {
      this.formExpectedDocuments.set('');
    }
  }

  submitPromotion(): void {
    const turnId = this.activeTurnId();
    if (!turnId || this.promoting()) {
      return;
    }
    const docs = this.formExpectedDocuments()
      .split(',')
      .map((doc) => doc.trim())
      .filter((doc) => doc.length > 0);
    this.promoting.set(true);
    this.promoteError.set(null);
    this.api
      .promoteFeedback(turnId, {
        caseId: this.formCaseId().trim(),
        question: this.formQuestion().trim(),
        referenceAnswer: this.formReferenceAnswer().trim(),
        expectedDocuments: docs,
        category: this.formCategory(),
      })
      .subscribe({
        next: (updated) => {
          this.promoting.set(false);
          this.activeTurnId.set(null);
          this.lastPromotedItem.set(updated);
          this.items.update((list) =>
            list.map((item) => (item.turnId === updated.turnId ? updated : item)),
          );
          this.pendingCount.update((c) => Math.max(0, c - 1));
          this.promotedCount.update((c) => c + 1);
          const promotedNum = Number.parseInt((updated.promotedCaseId ?? 'G120').slice(1), 10);
          const currentSuggested = Number.parseInt(this.suggestedNextCaseId().slice(1), 10);
          if (!Number.isNaN(promotedNum) && promotedNum >= currentSuggested) {
            this.suggestedNextCaseId.set(`G${String(promotedNum + 1).padStart(3, '0')}`);
          }
        },
        error: (error: unknown) => {
          this.promoting.set(false);
          this.promoteError.set(extractPromotionError(error));
        },
      });
  }

  copyJsonl(item: FeedbackListItem): void {
    if (!item.promotedCaseJsonl) {
      return;
    }
    void navigator.clipboard?.writeText(item.promotedCaseJsonl);
    this.copiedTurnId.set(item.turnId);
  }

  downloadJsonl(item: FeedbackListItem): void {
    if (!item.promotedCaseJsonl || !item.promotedCaseId) {
      return;
    }
    const blob = new Blob([`${item.promotedCaseJsonl}\n`], {
      type: 'application/x-ndjson;charset=utf-8',
    });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `${item.promotedCaseId}.jsonl`;
    link.click();
    URL.revokeObjectURL(url);
  }
}
