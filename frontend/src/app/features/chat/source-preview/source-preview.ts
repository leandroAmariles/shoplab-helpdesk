import { Component, computed, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import {
  MAT_DIALOG_DATA,
  MatDialogActions,
  MatDialogClose,
  MatDialogContent,
  MatDialogTitle,
} from '@angular/material/dialog';
import { MarkdownRenderer } from '../../../core/markdown/markdown-renderer';
import { ChatSource } from '../chat-message';

/**
 * Content of the dialog that opens a fragment: where it comes from and what it says. The text is untrusted corpus
 * content, so it goes through the same sanitizing renderer as the answers, without citations (ADR-0014).
 */
@Component({
  selector: 'app-source-preview',
  imports: [MatButtonModule, MatDialogActions, MatDialogClose, MatDialogContent, MatDialogTitle],
  templateUrl: './source-preview.html',
  styleUrl: './source-preview.scss',
})
export class SourcePreview {
  protected readonly source = inject<ChatSource>(MAT_DIALOG_DATA);
  private readonly renderer = inject(MarkdownRenderer);

  protected readonly html = computed(() => this.renderer.render(this.source.text));
}
