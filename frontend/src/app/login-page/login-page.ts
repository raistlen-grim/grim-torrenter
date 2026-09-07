import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { PasswordModule } from 'primeng/password';

import { AuthService } from '../services/auth.service';
import { TorrentEventsService } from '../services/torrent-events.service';

/**
 * The one screen reachable with no token at all (design_docs/0061) - rendered standalone,
 * outside the app shell entirely (see App's own isLoginRoute). A single shared password, no
 * username field - this app has exactly one torrent list per deployment, not one per account.
 * On success, starts TorrentEventsService itself (App's constructor deliberately skips that
 * when auth is required and no token exists yet) before navigating into the app.
 */
@Component({
  selector: 'app-login-page',
  imports: [ButtonModule, PasswordModule, ReactiveFormsModule],
  templateUrl: './login-page.html',
  styleUrl: './login-page.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LoginPage {
  private readonly authService = inject(AuthService);
  private readonly torrentEventsService = inject(TorrentEventsService);
  private readonly router = inject(Router);

  readonly form = new FormGroup({
    password: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
  });
  readonly submitting = signal(false);
  readonly errorMessage = signal<string | null>(null);

  submit(): void {
    if (this.form.invalid || this.submitting()) {
      return;
    }
    this.submitting.set(true);
    this.errorMessage.set(null);
    this.authService.login(this.form.controls.password.value).subscribe({
      next: () => {
        this.torrentEventsService.connect();
        this.router.navigateByUrl('/');
      },
      error: (error: unknown) => {
        this.submitting.set(false);
        const isTooManyAttempts = error instanceof HttpErrorResponse && error.status === 429;
        this.errorMessage.set(
          isTooManyAttempts ? 'Too many failed attempts - wait a moment and try again.' : 'Incorrect password.',
        );
      },
    });
  }
}
