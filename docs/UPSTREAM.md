# Upstream: Spicy Lyrics

Melisma is a port of [Spicy Lyrics](https://github.com/Spikerko/spicy-lyrics) by Spikerko. This records
which version it is based on, so a later sync knows where to start comparing.

**Current base: 6.3.98**, commit `c22a9d7324` (2026-09-26).

To see what upstream has changed since:

```
git clone --filter=blob:none https://github.com/Spikerko/spicy-lyrics.git
git -C spicy-lyrics log --oneline c22a9d7324..main -- src/utils/Lyrics src/utils/Scrolling
```

## History

### Ported from 6.3.12 (2026-09-08)

A shallow clone of `main` at `1b6124667d` (2026-09-06): tag 6.3.12 plus one commit, "Fall back to
player state when getPositionState stalls". The renderer, animation curves, lyric model and TTML
dialect come from here.

### Synced with 6.3.98 (2026-09-28)

Compared `1b6124667d..c22a9d7324`. Most of it is Spotify-client work with no counterpart here.

Taken:

- **A backing vocal's romanization stays with the backing vocal.** Apple writes a line's
  transliteration in one `<text>`, backing vocal included; it was appended to the lead's romanization
  and the backing vocal had none. (6.3.20, "Keep romanized-only lines")
- **A line written only as its romanization is kept.** A `<p>` holding nothing but an `x-roman` span
  was dropped. (6.3.20)

Already handled here:

- Spaces between per-word transliteration spans, and entries with no spans: the parser here keeps
  every text node, so the bug upstream fixed never applied.
- Several backing vocals on one line: all are kept, merged into one backing line.
- A response left with no lines is treated as not found.
- Spring-driven auto-scroll that keeps its velocity when the target moves (6.3.50 "Smooth Scrolling"):
  the scroll here has always been a spring.

Not taken:

- **Early scroll** (6.3.50, off by default upstream): starts scrolling to a line before it lights up.
  Would be a new setting; not asked for.
- **Seek fade-in compensation** (6.3.50): seeks 300 ms early because the Spotify desktop client
  fades audio in after a seek. Not measured on Android players.
- Everything tied to the Spotify desktop client or Spicy Lyrics' own service: the DOM animator and
  virtualizer, playbar and Now Playing view buttons, position-source stall recovery, the lyrics API
  session and its 401 retry, update and notice dialogs, CSS.
