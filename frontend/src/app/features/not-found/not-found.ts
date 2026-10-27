import { Component } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-not-found',
  imports: [MatCardModule, MatButtonModule, RouterLink],
  template: `
    <mat-card appearance="outlined">
      <mat-card-header>
        <mat-card-title>
          <h1>Página no encontrada</h1>
        </mat-card-title>
      </mat-card-header>
      <mat-card-content>
        <p>La dirección que buscas no existe o fue movida.</p>
      </mat-card-content>
      <mat-card-actions>
        <a mat-button routerLink="/">Volver al inicio</a>
      </mat-card-actions>
    </mat-card>
  `,
})
export class NotFound {}
