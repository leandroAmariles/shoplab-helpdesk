import { TextFieldModule } from '@angular/cdk/text-field';
import { Component, computed, ElementRef, input, model, output, viewChild } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { COUNTER_THRESHOLD, MAX_MESSAGE_LENGTH } from '../chat-message';

@Component({
  selector: 'app-message-input',
  imports: [TextFieldModule, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule],
  templateUrl: './message-input.html',
  styleUrl: './message-input.scss',
})
export class MessageInput {
  readonly draft = model('');
  readonly canSend = input(false);
  readonly streaming = input(false);
  readonly send = output<void>();
  readonly stop = output<void>();

  protected readonly maxLength = MAX_MESSAGE_LENGTH;
  protected readonly length = computed(() => this.draft().length);
  protected readonly showCounter = computed(() => this.length() >= COUNTER_THRESHOLD);
  protected readonly overLimit = computed(() => this.length() > MAX_MESSAGE_LENGTH);

  private readonly field = viewChild.required<ElementRef<HTMLTextAreaElement>>('field');

  focus(): void {
    this.field().nativeElement.focus();
  }

  protected onInput(event: Event): void {
    this.draft.set((event.target as HTMLTextAreaElement).value);
  }

  /** Enter sends, Shift+Enter inserts a line break. */
  protected onEnter(event: Event): void {
    const key = event as KeyboardEvent;
    if (key.shiftKey || key.isComposing) {
      return;
    }
    key.preventDefault();
    this.submit();
  }

  protected submit(): void {
    if (this.canSend()) {
      this.send.emit();
    }
  }
}
