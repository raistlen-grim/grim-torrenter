/** Copies text to the clipboard, falling back to the legacy execCommand('copy') approach when
 * the async Clipboard API isn't available. navigator.clipboard is only defined in secure
 * contexts (HTTPS, or http://localhost) - GrimTorrenter is typically reached over plain HTTP on
 * a LAN IP/hostname when self-hosted, where navigator.clipboard is simply undefined and
 * navigator.clipboard.writeText(...) throws synchronously, before any .then()/.catch() handler
 * on the call ever runs. Always returns a promise so callers can handle success/failure
 * uniformly regardless of which path was actually used. */
export function copyToClipboard(text: string): Promise<void> {
  if (navigator.clipboard?.writeText) {
    return navigator.clipboard.writeText(text);
  }
  return new Promise<void>((resolve, reject) => {
    const textarea = document.createElement('textarea');
    textarea.value = text;
    textarea.style.position = 'fixed';
    textarea.style.opacity = '0';
    document.body.appendChild(textarea);
    textarea.focus();
    textarea.select();
    try {
      if (document.execCommand('copy')) {
        resolve();
      } else {
        reject(new Error("execCommand('copy') failed"));
      }
    } catch (e) {
      reject(e);
    } finally {
      document.body.removeChild(textarea);
    }
  });
}
