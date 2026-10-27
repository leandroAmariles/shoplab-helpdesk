import { Component } from '@angular/core';
import { MatCardModule } from '@angular/material/card';

@Component({
  selector: 'app-home',
  imports: [MatCardModule],
  template: `
    <mat-card appearance="outlined">
      <mat-card-header>
        <mat-card-title>
          <h1>ShopLab Helpdesk</h1>
        </mat-card-title>
        <mat-card-subtitle>Asistente de soporte al cliente</mat-card-subtitle>
      </mat-card-header>
      <mat-card-content>
        <p>La aplicación base está lista. El chat con respuesta en streaming llegará en la siguiente historia.</p>
      </mat-card-content>
    </mat-card>
  `,
})
export class Home {}
