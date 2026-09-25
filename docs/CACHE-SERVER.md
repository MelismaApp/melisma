# Cache server contract

What the app sends to a lyrics server of your own, and what it will accept back. Set the
URL under **Settings → Developer → Cache server URL**, which only appears once **Developer
options** is on.

This exists so the server can be written against a fixed target. Nothing here is required
to use the app, and with the developer switch off none of it runs — a URL left behind in
preferences stops being used rather than quietly answering.

`CacheServerContractTest` checks this against a response a real server actually sent, so if
either side drifts the build says so. A cache server returning something unreadable otherwise
looks exactly like a track nobody has transcribed, which is the failure worth making loud.

## Why a server at all

The free sources are the reason this app works without an account, and two of them are
somebody's goodwill: LRCLIB asks not to be hammered, and the AMLL endpoint is run by
volunteers. A cache in front of them turns *one request per listener per play* into *one
request per song, ever*. It is also the only way to use a source that is rate-limited per
token rather than per user.

## The request

```
GET {baseUrl}/v1/lyrics?title=…&artist=…&album=…&durationMs=…&spotifyId=…
```

| Parameter | Always sent | Notes |
|---|---|---|
| `title` | yes | The title as the media session reported it, not cleaned up. |
| `artist` | yes | May be several names in one string, as the player published it. |
| `album` | when known | Absent for a queue entry, which publishes none. |
| `durationMs` | when known | Milliseconds. Absent or 0 when the player did not say. |
| `spotifyId` | when known | 22-character track id, when playing from Spotify. |

Everything is URL-encoded with `%20` for spaces. `Accept: application/json,
application/xml, text/plain`.

## Authentication

By default the app sends none, because a lookup cannot expose anything: it carries a title and
an artist, and nothing it can ask for returns a credential. A server on your own network should
let that through.

A server reachable from further away should not — one holding an Apple Music token has no
business answering strangers. Put its key in **Cache server key** and the app sends
`Authorization: Bearer <key>`. Left empty, the header is absent rather than empty, so the same
build works against both.

That is the split
[melisma-server](https://github.com/MelismaApp/melisma-server) implements: its
admin surface, the only part that can read a token, always demands the key; a lookup from the
local network does not.

A request may arrive for a track that is *about to* play rather than one playing now — the
app prefetches the next queued track when the player publishes a queue. Those look
identical and need no special handling.

## The response

`200` with a body in any of these shapes. The app sniffs rather than trusting a content
type, because the three cannot be confused: JSON opens with `{`, TTML with `<`, and LRC
with a timestamp or plain text.

**TTML** — the best case. Word-level timings, duet agents, background vocals, readings and
translations all survive. This is the format Apple Music and the AMLL database use:

```xml
<tt xmlns="http://www.w3.org/ns/ttml" itunes:timing="Word">…</tt>
```

**LRC**, plain or enhanced (`<mm:ss.xx>` word tags):

```
[00:12.34]Line one
[00:15.00]<00:15.00>Word <00:15.40>by <00:15.90>word
```

**JSON envelope** wrapping either:

```json
{
  "status": 200,
  "data": {
    "format": "ttml",
    "lyrics": "<tt …>",
    "source": "amll",
    "providerName": "AMLL TTML DB · by cybaka520"
  }
}
```

- `lyrics` (or `body`) is the only required field.
- `format` may be `ttml` or `lrc`; it is a hint, and the sniffer wins if it disagrees.
- `providerName` is shown under the last line as the credit. Use it to name where the
  lyrics actually came from — `source` is used the same way if `providerName` is absent.
  Without either, every track claims to come from a cache server, which loses the
  attribution the upstream sources are owed.
- Wrapping in `data` is optional; the fields may sit at the top level.

**No lyrics**: any non-2xx, or an empty body. `404` is the obvious one. The app treats a
failure and a miss identically — the provider simply contributes nothing to that track's
lookup — so there is no need to distinguish them on the wire.

**Not lyrics**: markup that is not TTML is refused rather than shown. The realistic accident is
a misconfigured server, or something in front of it, answering `200` with an error page; a
lyric line reading "502 Bad Gateway" is worse than finding nothing. Plain text with no
timestamps *is* accepted, because unsynced lyrics are a real answer.

## Artwork, tempo and Canvas

Optional, and behind its own switch — **Developer → Ask the server for artwork, tempo and Canvas**.
Off, none of this is called.

Read-only. The server collects these for itself, every time it looks a track up, so the tokens
live on that one machine rather than on every phone. There is nothing for the app to contribute
and no write endpoint to secure.

The reason it exists: a Spotify access token is good for about an hour and an Apple developer
token for a few months, but a cover URL, an ISRC and a tempo, once known, are true forever. The
server is the thing that outlives the tokens.

```
GET {baseUrl}/v1/extras?title=…&artist=…&album=…&durationMs=…&spotifyId=…
```

Same parameters and same optional `Authorization: Bearer <key>` as the lyrics lookup, and like a
lookup it needs no key from the local network. Answer:

```json
{
  "coverUrl": "https://…/cover.jpg",
  "artistImageUrl": "https://…/artist.jpg",
  "tempo": 87.5,
  "isrc": "JPU901800227",
  "canvasUrl": "https://canvaz.scdn.co/upload/artist/…/video/….cnvs.mp4",
  "canvasThumbnails": [{ "width": 144, "height": 256, "url": "https://i.scdn.co/image/…" }],
  "palette": { "bgColor": "1f1f24", "textColor1": "ffffff" },
  "analysis": { "beats": [], "bars": [], "sections": [] },
  "metadata": { "composerName": "…", "albumName": "…" }
}
```

- Every field is optional; an answer with none of them is the same as a 404.
- `cover` and `artistImage` are accepted as aliases, and a `data` wrapper is allowed, as with the
  lyrics.
- The URLs may point anywhere — including back at the server, which is how it serves a copy it
  holds rather than a link to somebody else's.
- `tempo` is beats per minute. It paces the animated background.
- `canvasUrl` is the track's Spotify Canvas video: the address only, never the file. It must be
  https on `*.scdn.co` and end in `.mp4`, or the app ignores it, and it is used only when the query
  carried a `spotifyId`, for that Spotify track. The app downloads the video from Spotify's CDN
  with no credential; one that no longer exists leaves the ordinary background showing.
- `canvasThumbnails` are still JPEG images of the Canvas on `i.scdn.co`, not smaller videos,
  portrait like the video and smallest first. The app shows the largest while the video downloads,
  and keeps showing it if the download fails. It must be https on `*.scdn.co` under `/image/`, and
  is ignored without a `canvasUrl`. Servers before melisma-server e4091c5 sent them as
  `canvasVariants`, with `width` and `height` swapped; both are read.
- `palette`, `analysis` and `metadata` are held whole and served whole. The app reads none of
  them yet; they are collected because the tokens are the scarce thing, not the storage, and
  `audio-attributes` — which carries the tempo, key, loudness and the beat, bar and section grids
  — was withdrawn from Spotify's public API in November 2024, so a cached copy is the only
  durable one there is.
- By default only asked when no token on the phone can answer. A live token is about the track
  playing now, where the server is a record of one that matched before.
- **Developer → Where the extras come from → Only the cache server** asks nothing else for
  artwork, tempo and Canvas, the way *Only the cache server* does for the lyrics. The two are
  separate settings for now; the lyrics one does not affect the extras.

## Asking the server about itself

```
GET {baseUrl}/v1/status
```

Behind **Developer → Test the server**, next to the one that tests the sources the app reaches
directly. It reports each of the server's own sources: off, needs a token, token expired, or
working, with the timing.

It exists because the other test cannot answer this. Pointing the app at a server puts every
source behind one hop, and "the server returned no lyrics" covers a source switched off, a token
that expired last week, and a track nobody has transcribed. No key needed from the local network,
and **no credential comes back** — only whether one works.

```json
{
  "ok": true,
  "sources": [
    { "id": "amll", "name": "AMLL TTML DB", "ok": true, "ms": 210, "detail": "reachable" },
    { "id": "spotify", "name": "Spotify", "ok": false, "detail": "the token has expired" }
  ]
}
```

A source the app has never heard of is still shown. The point of the screen is to report what is
there.

## Language tags

Which language a Chinese-script track is sung in: Mandarin, Taiwanese Hokkien or Cantonese. The
script cannot say, and it decides how the app romanizes the song — pinyin or Tâi-lô
([ROMANIZATION.md](ROMANIZATION.md#taiwanese-hokkien)).

**Read.** Every `/v1/lyrics` answer may carry, a 404 included:

```
X-Lyrics-Language: nan
X-Lyrics-Language-Source: tagged
```

- `nan` is Hokkien, `zh` Mandarin, `yue` Cantonese (ISO 639-3). Anything else is ignored.
- A miss carries it too: a track tagged Hokkien is still Hokkien when another source supplies the words.
- The app keeps it on the phone, per track, and uses it before its own detector. A choice made on the
  phone under **This track → Read this song as** is used before both.

**Write.** Choosing Mandarin, Hokkien or Auto under **Read this song as** also sends:

```
PUT {baseUrl}/v1/language
Authorization: Bearer <admin key>
Content-Type: application/json

{ "spotifyId": "…", "isrc": "…", "title": "…", "artist": "…", "album": "…", "durationMs": 208000,
  "language": "nan" }
```

- `language` is `nan`, `zh`, or `yue`, or an explicit `null` for Auto, which clears the tag so the
  server decides for itself again. A missing `language` is a 400.
- `204` saved. `401` no key or a wrong one, including from the local network. `403` a key that is not
  the admin key — a user key cannot tag.
- A tag belongs to the recording: set or cleared from any release, it applies to every release with
  the same ISRC.
- Sent once per choice and never retried. Without a key nothing is sent, and the choice stays on the
  phone.

## What the app does with it

In **Alongside the others** mode the server is asked in parallel with every enabled source, and
appears in **Where lyrics come from** so it can be ranked and switched off like any of them. First by
default, because an answer it already holds cost nobody a request. A server that is down, slow or
wrong costs nothing either way, which is what makes this the mode to develop against.

Ranking decides between answers of the same kind. Every source is asked at once; word-synced beats
line-synced beats untimed, and a scrap of a song loses to most of one, because neither of those is a
matter of taste. Past that the order rules — so putting the server first means "when two answers are
equally good, prefer the one already in hand", not "ignore the others".

In **Only the cache server** mode nothing else is asked. A track with no lyrics means the
server could not answer it — which is the point.

In both modes:

- A file the user imported for a track still wins outright, and is answered without
  asking anything.
- Answers are cached on the phone for 30 days, so the server is asked once per track per
  month at most. A **miss is not cached at all** — a track it could not answer today is
  asked about again on the next play, which is what you want while the server is filling
  up. **Settings → This track → Look this track up again** drops a stored answer and forces
  a fresh request, which is the button to use while iterating.
- Each provider gets 12 seconds before it is abandoned.

## The contract is read-only, deliberately

The app only ever asks this server questions, with one exception: a [language tag](#language-tags),
sent with the admin key when you choose one. That is a decision rather than an omission — so if you
are extending either side, this is the constraint to design around.

The objections below do not apply to the tag. The server cannot hear which language a song is in, so
it does not already have the answer. Only the admin key is accepted, so a user key or an open network
cannot poison anything. And it is one call site, `CacheServerProvider.putLanguage`, run by one
control.

It came up as a real proposal: have the app report "these timings did not fit what was playing" so
the server's cross-source checks could use playback evidence. It was declined, and the reasons are
worth keeping:

- **The server already has the evidence.** Every lookup carries `durationMs`, the length the player
  reported, and the server stores it. Whether a document's timings run past the end of the copy
  actually playing is therefore answerable server-side, over the whole archive, retroactively, with
  no protocol change. The one thing the app was going to report was already in hand.
- **A write path costs more than it returns.** A lookup needs no key on a local network, because a
  lookup cannot expose anything. An unauthenticated *write* could be used to poison the quality data
  the ranking depends on — flag one source as wrong on every track and you degrade every answer. So
  it would have to demand the key, which limits it to people who configured auth.
- **"No write path exists" is verifiable; "writes only happen when a toggle is on" is a promise.**
  The first is a property you can confirm by reading the code. The second has to be re-audited at
  every call site forever.
- **The app already acts on it.** A document whose timings do not fit the track is demoted on the
  phone, at lookup time, by `TimingSanity`. Reporting it onward so the server can agree later helps
  nobody who has already seen it demoted.

What this gives up is real but small: whether the *words* were wrong, and whether the reader was
frustrated. Both are only reachable by inference — from seeks, from the sync offset — and neither
survives contact with ordinary listening. People seek most in songs they like, and the sync offset is
a Bluetooth latency control, so it says something about someone's earbuds and nothing about a
document. **Look this track up again** is the one honest signal of "these are wrong", and it stays in
the app, where the reader who pressed it is the one who benefits.

## Redistribution

Worth being deliberate about, because caching solves a rate limit and not a licence. A
private server, one user, your own credentials, is a defensible position. The moment other
people query it, it is a redistribution service for content you do not have the rights to
redistribute — which is a different thing entirely, whatever the technical design.

The community-positive version of the same idea already exists: the
[AMLL TTML Database](https://github.com/amll-dev/amll-ttml-db) is CC0 and accepts
contributions.
