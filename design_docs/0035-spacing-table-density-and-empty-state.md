# 0035 — Spacing scale, table density, and empty-state copy (style pass, part 3)

**Status:** Accepted

## Decision

The remaining pieces of the visual style pass confirmed in [[0033-per-entry-action-feedback]]/[[0034-ink-weight-status-display]]'s
scoping: the style guide's fine spacing rhythm, its table density/hairline conventions, and
its empty-state pattern (icon + one sentence + themed heading, confirmed in scope per the
earlier AskUserQuestion round - responsive/mobile breakpoints and middle-text-truncation
were explicitly ruled out of scope in that same round).

### Spacing scale (`styles.scss`)

Six CSS custom properties (`--space-1` through `--space-8`: 3/7/10/14/20/27px) taken
directly from the guide's own "Grid, spacing & density" section. Applied only to **fine,
component-level gaps** (icon-to-label, button groups, meta-row gaps, small margin-tops)
across every stylesheet touched this session (`status-indicator`, `torrent-row`,
`torrent-list`, `torrent-detail`, `files-tab`, `trackers-tab`, `piece-map`) - not to
larger structural section/page margins, which the guide's own markup also keeps as
separate, bigger ad hoc values (its page shell uses 24px/40px directly, not this scale).
`torrent-detail.scss`'s `:host` page padding (24px) was already coincidentally aligned
with the guide's own page-container padding and needed no change.

### Table density (`size="small"` on every `p-table`)

**Note (later superseded):** `files-tab`/`peers-tab`/`trackers-tab` no longer use `p-table`
at all - [[0044-torrent-detail-drawer]] replaced all three with a stacked card list once
`TorrentDetail` moved into a ~430px drawer, too narrow for their original column counts.
`torrent-list`'s own `p-table` (the main list, not the detail tabs) is unaffected and still
follows the density rule below.

The guide calls for a 34px row height with tight padding. Rather than hand-writing CSS
padding/height overrides, every `p-table` in the app (`torrent-list`, `files-tab`,
`peers-tab`, `trackers-tab`) now sets PrimeNG's own built-in `size="small"` input, which
switches to Aura's pre-defined compact padding tokens (`0.375rem 0.5rem` vs. the default
`0.75rem 1rem`) - a supported PrimeNG mechanism, not a custom override, consistent with
[[0032-style-guide-and-primeng-theme]]'s vanilla-first rule. Gets close to the guide's 34px
without hardcoding a height. Zebra striping was already off everywhere (PrimeNG's
`stripedRows` defaults to false and nothing sets it) - no change needed there. Hairline
divider color was left as Aura's own default rather than hand-tuned to the guide's literal
"8% ink" - a subtle color-only nuance not worth a preset override for.

### Empty-state pattern (`torrent-list.html`'s `emptymessage` template)

Was a single plain sentence in a `<td>`. Now: a centered icon (PrimeIcons `pi-inbox` -
there's no literal skull equivalent, and building/bundling a second icon library for one
decorative glyph would violate 0032's own maintenance-cost reasoning for why Lucide was
dropped in the first place), a themed heading ("Nothing to reap" - one of the guide's
explicitly-allowed themed-copy spots), and plain-language body text describing the app's
*actual* add-torrent flow (upload button + magnet-paste field in the toolbar above) -
adapted from the guide's own example text, which assumes a drag-anywhere behavior this app
doesn't have. No duplicate "Add torrent" CTA button in the empty state itself, since the
real controls already sit directly above the table in the toolbar - a second button doing
the same thing would be redundant, and the guide's own "one primary action per view" rule
argues against it.

**Only the top-level torrent-list empty state got this treatment.** The three detail-tab
empty states (Files/Peers/Trackers, e.g. "No connected peers.") stay as plain single-line
text - a lower-stakes, often-expected transient state (a torrent legitimately having no
peers connected right now isn't emotionally equivalent to "you have zero torrents"), not
worth the same visual weight.

## Alternatives considered

- **Hand-written CSS padding/row-height overrides for table density** - rejected in favor
  of `size="small"`, PrimeNG's own supported mechanism for exactly this.
- **Reaching for Lucide for the empty-state icon** to get a literal skull - rejected per
  0032's existing icon-substitution precedent; not worth a second icon library for one glyph.
- **A second "Add torrent" button inside the empty state** - rejected; redundant with the
  toolbar's existing controls, and against the guide's own "one primary action" rule.
