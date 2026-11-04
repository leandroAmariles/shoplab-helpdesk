import { Component, input, output } from '@angular/core';
import { ChatSource } from '../chat-message';

/** The sources of an answer under it; activating one asks the parent to open its fragment. */
@Component({
  selector: 'app-source-list',
  templateUrl: './source-list.html',
  styleUrl: './source-list.scss',
})
export class SourceList {
  readonly sources = input.required<readonly ChatSource[]>();
  /** «Fuentes» for the cited ones, «Fuentes consultadas» when the model cited none. */
  readonly label = input('Fuentes');
  readonly open = output<ChatSource>();
}
