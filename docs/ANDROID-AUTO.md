# Android Auto

The car screen is the phone's screen. Same renderer, same syllable fill, same drifting background,
same album art beside the words in Cinema view — because it is literally the same Compose code drawn
on a surface the car hands over, not a second implementation of it.

Everything it shows follows the settings on your phone. There is nothing to toggle from the driver's
seat, which is the point: a car is the worst possible place to be changing anything.

> ⚠️ Moving words are as easy to stare at as they are to glance at. The app says so at the start of
> every drive, and it is worth saying here too: if the animation pulls at your eyes, **Settings →
> Android Auto → Plain screen in the car** replaces it with two lines of static text.

## Setting it up

Melisma is not on Google Play, so Android Auto will not load it until you tell Android Auto that you
want apps it did not get from the store.

1. On the phone: **Settings → Connected devices → Android Auto** (older versions have a standalone
   Android Auto app).
2. Scroll to the bottom and tap **Version and permission info** about ten times, until it offers to
   allow development settings. Accept.
3. Open the **⋮** menu at the top right → **Developer settings**.
4. Turn on **Unknown sources**.
5. Plug in, or connect wirelessly. Melisma appears in the car's app launcher.

One time per phone, and it is the same switch any sideloaded car app needs.

## What you get

| | |
|---|---|
| **The lyrics, moving** | The real renderer: the syllable sweep, the letter emphasis on a held note, the depth blur, the interlude dots. Your type size, font, romanization, furigana, translation and sync offset, exactly as set on the phone. |
| **The background** | The same drifting colour field from the album art, paced by the song's tempo — held still if battery saver is on and you have left that switch alone. |
| **Cinema view** | If the phone is in Cinema view, the car shows the cover, the title, the artist and a progress line beside the words, on the side the phone was told to use. |
| **The controls** | Play, pause, previous and next, drawn by the car rather than by us — bigger targets, in the place the driver already looks for them. Only the ones the player actually accepts. |
| **A warning** | At the start of every drive, not once ever. It is not a licence agreement to click past. |

## What it will not do

**Only one button can have a label.** `PaneTemplate.setActionStrip` validates against
`ACTIONS_CONSTRAINTS_SIMPLE`, which permits exactly one action with a custom title and throws on the
second — so play/pause carries the title, being the one whose meaning changes, and the skips are
icon-only. `Action` has no content description of its own, so that limit is also the accessibility
ceiling; it is the library's, not a preference.

**Nothing on the surface is tappable, and that is the platform being right.** A car host does not
deliver touches to an app's surface as ordinary events — it sends map gestures through
`SurfaceCallback` instead — so a button drawn there would be a button that does nothing. Every
control therefore lives in the car's own action strip. The same rule removes tap-to-seek: that is a
phone gesture, and reaching for a lyric line at 70mph is not a feature.

**No settings.** See above.

## Two screens, and when you get which

| | |
|---|---|
| **The full renderer** | The default, when the host hands over a surface *and* understands car API level 7 — the level `MapWithContentTemplate` needs. On anything older the app does not even ask for a surface, since no template it could return would carry one. |
| **Two lines of text** | Everything else: an older host, a head unit that declines a surface, or *Plain screen in the car*. The words now, the words next, and the same controls. |

The fallback is a complete answer rather than a broken one, and it is worth knowing that the logic
deciding *which* words appear is shared with neither screen's drawing:
[`CarGlance`](../app/src/main/java/com/melisma/app/car/CarGlance.kt) is a pure function of the
playhead and the settings, so it can be tested without a car — which is where the interesting cases
live. An interlude says "Instrumental" rather than leaving the last line up for thirty seconds; a
gap under three seconds leaves the words where they are, because blanking between two lines would be
a flicker in the corner of your eye; a background vocal never becomes the current line, because it
sits inside its lead line's window and would flip the row twice a second.

## How it works

The Car App Library gives most apps templates and nothing else — rows and panes the host draws. One
template is different: `MapWithContentTemplate` hands the app a `Surface` to render a map onto, and
that is a canvas like any other. So:

```
host Surface ──► VirtualDisplay ──► Presentation ──► ComposeView ──► CarLyricsContent
                                                                     (LyricsView + DynamicBackground)
```

`VirtualDisplay` + `Presentation` + `ComposeView` is the documented way to put Views on a car
surface, and it is why the car screen cannot drift out of step with the phone: there is one renderer,
one background, one settings object. A `ComposeView` outside an activity needs a `LifecycleOwner` and
a `SavedStateRegistryOwner`; the car `Session` is both, so the composition lives and dies with the
connection to the car.

The lyrics are laid out inside the rectangle the host reports through `onVisibleAreaChanged`, so they
sit in the gap rather than under the header and the action strip.

### The category, and why this can never be on Google Play

The library admits apps in seven categories — navigation, POI, IoT, weather, media, messaging,
calling — and there is no category for lyrics. `MEDIA` is not it: that one is for apps that browse and
play their own catalogue through a `MediaBrowserService`, and Melisma owns no catalogue and plays
nothing.

So the manifest declares **POI**, because POI is the category that can reach
`MapWithContentTemplate`. The category is chosen for the template it unlocks rather than for
describing the app, and drawing lyrics on a surface meant for a map is a liberty taken deliberately:
it is the only way a car screen can show the same words moving the same way the phone does.

Stated plainly so nobody has to discover it: **this will never be a Play-listed Android Auto app.**
Google has no shelf to put it on. For an app distributed as an APK from a releases page that changes
nothing about who can use it.

### Three things that are easy to get wrong

**The car screen is not a window.** The app releases its notification listener after ten quiet
minutes, where quiet means "nothing playing and no window of ours on screen". A car session is not a
window, so it sets `AppContainer.carConnected` and the idle watch treats either as a reason to keep
watching. Without that, the app would stand itself down mid-drive and the lyrics would stop for good.

**The car screen may be the only thing that ever opens the app.** Plug the phone in on the way to
work and no activity of ours is ever created, so the session starts the media repository and rebinds
the notification listener exactly as `MainActivity.onStart` does.

**Nothing emits when a playhead advances.** A media session publishes a position and a timestamp;
everything after that is arithmetic. The surface path gets this for free — it is a frame loop, like
the phone. The templated fallback recomputes on a half-second tick and asks the host to redraw only
when the rendered content changed, which is once a line: template updates are throttled by the host,
and an app that invalidates on a timer rather than on a change gets throttled for nothing. There is
also a one-second floor, because a fast verse can put two lines inside a second.

## Testing it without a car

The Desktop Head Unit runs the car screen on your computer:

```bash
adb forward tcp:5277 tcp:5277
$ANDROID_HOME/extras/google/auto/desktop-head-unit
```

That needs **Start head unit server** in the same Android Auto developer settings. Pair it with
`:fakeplayer` (see [DEVELOPING.md](DEVELOPING.md)) and you have lyrics on a car screen with no car
and no music service.

`CarGlanceTest` covers the part that can be tested without any of that: the intro, the instrumental
break, the breath between two lines, background vocals, romanization composed per syllable, the sync
offset, and every state that is not words at all.
