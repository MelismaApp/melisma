# Installing Melisma

Melisma is an APK from [GitHub Releases](https://github.com/MelismaApp/melisma/releases/latest),
not a Play Store app, so Android puts three things in the way that it would not for a store app.
None of them is a fault; each has a switch.

1. [Allow the install](#allow-the-install)
2. [Play Protect](#play-protect)
3. [Notification access, and the greyed-out switch](#notification-access)

Then play something. Lyrics appear.

## Allow the install

Open the downloaded `melisma-x.y.z.apk`. If Android says the browser or file manager is not allowed
to install unknown apps, tap **Settings**, turn on **Allow from this source**, and go back. It is a
per-app switch on Android 8 and newer, also under *Settings → Apps → Special app access → Install
unknown apps*; the exact names vary by phone maker.

## Play Protect

Play Protect may warn about the APK, or offer to send it for scanning. It does this because the app
asks for notification access, which Android treats as sensitive — see below for what that access is
used for. Choose **Install anyway** (sometimes behind **More details**), and answer the scanning
question either way.

Do not turn Play Protect off. The exception is for this one app, and only for an APK from the
Releases page. Every release is signed with the project key; [RELEASING.md](RELEASING.md) says how
to check one.

## Notification access

Android shares what is playing in other apps — the title, artist, cover and live position of a
media session — only with apps that hold **notification access**. That is all Melisma uses it for.
The service that holds the permission never reads a notification: its `onNotificationPosted` is
empty. You can turn the access off any time; the app asks again when opened.

Tap **Open notification access** in the app, find Melisma in the list, turn it on, and come back.
The app notices on its own.

### The switch is greyed out, or says "Restricted setting"

On Android 13 and newer, Android blocks notification access for apps installed from a browser or a
file manager until you allow it once:

1. Tap the greyed-out switch anyway, so Android shows the restriction message.
2. Open Android's *Settings → Apps → Melisma*.
3. Tap the **⋮** menu at the top, then **Allow restricted settings**, and confirm with your PIN or
   fingerprint. The item only appears after step 1.
4. Back in the app, tap **Open notification access** again. The switch now works.

The in-app gate has an **Open Melisma's app settings** button that lands on step 2's screen. Apps
installed through an updater such as Obtainium are usually not restricted.

## Updates

The app checks GitHub for new releases itself and offers to install them (*Settings → About*).
Only an update signed with the release key installs. [Obtainium](https://obtainium.imranr.dev)
works too, pointed at this repository, for anyone who already updates their sideloaded apps that way.

If you installed the APK so that Google Play is recorded as the installer — the Android Auto case,
see [ANDROID-AUTO.md](ANDROID-AUTO.md) — install updates the same way rather than from the in-app
prompt, which would record Melisma as the installer again.

## If lyrics do not appear

- **"Nothing playing"** with music on: the player may be switched off under *Settings → Players to
  follow*, or notification access was revoked — the gate comes back when it is.
- **The player's name at the top is not your music app:** another app is publishing a media
  session of its own and winning. Switch it off under *Players to follow*, and *Settings →
  Developer → What players publish* shows everything each session says.
- **"No lyrics found":** *Settings → This track → Look this track up again* asks every source
  afresh, and *Settings → Developer → Test the sources* says what each one answered. If nothing has
  the song, a `.lrc` or `.ttml` file of your own always wins.
