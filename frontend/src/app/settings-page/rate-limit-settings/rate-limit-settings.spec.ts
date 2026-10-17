import { Settings } from '../../models/settings.model';
import { buildRateLimitSettingsForm, rateLimitSettingsPatch } from './rate-limit-settings';

function settings(overrides: Partial<Settings>): Settings {
  return {
    uploadRateLimitBytesPerSec: 0,
    downloadRateLimitBytesPerSec: 0,
    rateLimitBurstSeconds: 0,
    rateLimitScheduleEnabled: false,
    rateLimitScheduleStart: '23:00',
    rateLimitScheduleEnd: '07:00',
    scheduledUploadRateLimitBytesPerSec: 0,
    scheduledDownloadRateLimitBytesPerSec: 0,
    ...overrides,
  } as Settings;
}

describe('rate limit settings form', () => {
  it('shows bytes/s as KiB/s, with the toggle on only for a real cap', () => {
    const form = buildRateLimitSettingsForm(settings({ uploadRateLimitBytesPerSec: 512 * 1024 }));
    expect(form.controls.uploadEnabled.value).toBe(true);
    expect(form.controls.uploadLimitKibPerSec.value).toBe(512);
    expect(form.controls.downloadEnabled.value).toBe(false);
    expect(form.controls.downloadLimitKibPerSec.disabled).toBe(true);
  });

  it('round-trips through the patch, sending 0 for a cap that is off', () => {
    const form = buildRateLimitSettingsForm(settings({ uploadRateLimitBytesPerSec: 512 * 1024 }));
    const patch = rateLimitSettingsPatch(form.getRawValue());
    expect(patch.uploadRateLimitBytesPerSec).toBe(512 * 1024);
    expect(patch.downloadRateLimitBytesPerSec).toBe(0);
  });

  /** A cap switched on at 0 used to save as "unlimited" - design_docs/0096. */
  it('is invalid while a cap is on with a value below 1', () => {
    const form = buildRateLimitSettingsForm(settings({}));
    expect(form.valid).toBe(true);

    form.controls.uploadLimitKibPerSec.enable();
    form.controls.uploadLimitKibPerSec.setValue(0);
    expect(form.valid).toBe(false);

    form.controls.uploadLimitKibPerSec.setValue(1);
    expect(form.valid).toBe(true);
  });
});
