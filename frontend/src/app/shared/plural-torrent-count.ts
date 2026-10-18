/** `N torrents selected` / `1 torrent selected` (the style guide's Copy section) - never
 * `torrent(s)` (the style guide's Voice rules: "Never pluralise with (s)"). Shared by
 * every spot that needs to say how many torrents something applies to, so they can't drift
 * into that forbidden pattern independently. */
export function pluralTorrentCount(count: number): string {
  return `${count} torrent${count === 1 ? '' : 's'}`;
}
