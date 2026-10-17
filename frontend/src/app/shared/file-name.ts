/** `archive.tar.xz` keeps its whole compound extension; anything else just splits on the final
 * dot. A leading dot (`.gitignore`) or no dot at all has no extension to split off. Lets a row
 * truncate the stem with an ellipsis while always keeping the extension visible (style guide row
 * anatomy: "the extension is preserved"). Tested in file-name.spec.ts. */
export function splitFileName(name: string): { stem: string; extension: string } {
  const lastDot = name.lastIndexOf('.');
  if (lastDot <= 0) {
    return { stem: name, extension: '' };
  }
  const secondLastDot = name.lastIndexOf('.', lastDot - 1);
  if (secondLastDot > 0) {
    const compound = name.slice(secondLastDot + 1).toLowerCase();
    if (COMPOUND_EXTENSIONS.has(compound)) {
      return { stem: name.slice(0, secondLastDot), extension: name.slice(secondLastDot) };
    }
  }
  return { stem: name.slice(0, lastDot), extension: name.slice(lastDot) };
}

const COMPOUND_EXTENSIONS = new Set(['tar.gz', 'tar.bz2', 'tar.xz']);
