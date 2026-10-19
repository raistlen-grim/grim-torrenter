import { EtaFields, etaDetail, etaText } from './format-eta.pipe';

function eta(overrides: Partial<EtaFields>): EtaFields {
  return { etaSeconds: null, etaStatus: 'ESTIMATE', unavailablePieces: 0, ...overrides };
}

describe('etaText', () => {
  it('humanizes a real estimate', () => {
    expect(etaText(eta({ etaSeconds: 4 * 3600 + 12 * 60 }))).toBe('4h 12m');
  });

  it('shows the reason when there is no estimate', () => {
    expect(etaText(eta({ etaStatus: 'NO_PEERS' }))).toBe('No peers');
    expect(etaText(eta({ etaStatus: 'UNAVAILABLE', unavailablePieces: 12 }))).toBe('12 unavailable');
    expect(etaText(eta({ etaStatus: 'CHOKED' }))).toBe('Waiting');
    expect(etaText(eta({ etaStatus: 'STALLED' }))).toBe('Stalled');
  });

  it('is an em dash when complete or not running', () => {
    expect(etaText(eta({ etaStatus: 'COMPLETE' }))).toBe('—');
    expect(etaText(eta({ etaStatus: 'NOT_RUNNING' }))).toBe('—');
  });
});

describe('etaDetail', () => {
  it('explains unavailable pieces, singular and plural', () => {
    expect(etaDetail(eta({ etaStatus: 'UNAVAILABLE', unavailablePieces: 1 }))).toContain('1 piece not held');
    expect(etaDetail(eta({ etaStatus: 'UNAVAILABLE', unavailablePieces: 3 }))).toContain('3 pieces not held');
  });

  it('is empty for a real estimate', () => {
    expect(etaDetail(eta({ etaSeconds: 60 }))).toBe('');
  });
});
