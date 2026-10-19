import { parseEventMessage } from './event-message';

const TORRENT = { infoHash: 'abc', name: 'n', state: 'SEEDING', labelIds: [] };

describe('parseEventMessage', () => {
  it('accepts each message type with a well-formed payload', () => {
    expect(parseEventMessage(JSON.stringify({ type: 'snapshot', payload: [TORRENT] }))?.type).toBe('snapshot');
    expect(parseEventMessage(JSON.stringify({ type: 'state-changed', payload: TORRENT }))?.type).toBe('state-changed');
    expect(
      parseEventMessage(JSON.stringify({ type: 'event', payload: { timestamp: '2026-10-18T00:00:00Z', type: 'ADDED' } }))
        ?.type,
    ).toBe('event');
    expect(parseEventMessage(JSON.stringify({ type: 'labels', payload: [{ id: '1', name: 'TV' }] }))?.type).toBe('labels');
    expect(
      parseEventMessage(
        JSON.stringify({ type: 'transfer', payload: { downloadRateBytesPerSec: 10, uploadRateBytesPerSec: 0, ratio: null } }),
      )?.type,
    ).toBe('transfer');
  });

  it('drops bad JSON, unknown types and non-string frames instead of throwing', () => {
    expect(parseEventMessage('{not json')).toBeNull();
    expect(parseEventMessage(JSON.stringify({ type: 'mystery', payload: {} }))).toBeNull();
    expect(parseEventMessage(new ArrayBuffer(4))).toBeNull();
    expect(parseEventMessage('null')).toBeNull();
  });

  it('drops a payload of the wrong shape', () => {
    expect(parseEventMessage(JSON.stringify({ type: 'snapshot', payload: TORRENT }))).toBeNull();
    expect(parseEventMessage(JSON.stringify({ type: 'snapshot', payload: [TORRENT, { name: 'no hash' }] }))).toBeNull();
    expect(parseEventMessage(JSON.stringify({ type: 'state-changed', payload: [TORRENT] }))).toBeNull();
    expect(parseEventMessage(JSON.stringify({ type: 'labels', payload: [{ id: 1, name: 'TV' }] }))).toBeNull();
    expect(parseEventMessage(JSON.stringify({ type: 'transfer', payload: { downloadRateBytesPerSec: '10' } }))).toBeNull();
  });
});
