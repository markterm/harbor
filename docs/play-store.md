# Play Store readiness

A review of the app against Google Play's policies (19 October 2026), what was changed for it, and
what's still to do in the Play Console. Nothing here has been submitted.

## Fixed in the app

- **Privacy policy.** Every app needs one, linked from the listing and, since Harbor asks for
  Bluetooth, from inside the app. Written in `docs/privacy-policy.md`; summarised in About &
  licences ▸ Privacy. It needs a public URL and a contact address before submission.
- **Notification permission.** `POST_NOTIFICATIONS` was declared but never requested. It's now
  asked for when "Keep playing in background" is switched on (Android 13+).
- **Backup.** Android backup now skips firmware packages (`res/xml/data_extraction_rules.xml`).
  With them included, a few kept firmwares would exceed the 25 MB backup quota and nothing,
  saves included, would be backed up.

## In good shape

- **Downloaded code (Device and Network Abuse).** Play forbids downloading executable code from
  outside Play, except code that runs in a virtual machine or interpreter without access to
  Android APIs. Firmwares run inside fm1-emu, an emulator of the FM-1's processor and peripherals
  with no access to Android APIs, the same position as console emulators already on Play. The JIT
  translates the emulated machine's instructions; it doesn't load Android code. Say this in the
  review notes.
- **No copyrighted firmware shipped.** M-VAVE's stock firmware is bring-your-own; every other
  firmware downloads from its author's or the Hub's link, with its licence shown. Harbor hosts
  none.
- **Affiliation.** "Not made by or affiliated with M-VAVE" is on the first-run screen and in
  About. The app's name, icon and panel carry no M-VAVE logo or wordmark; "FM-1" and "M-VAVE"
  are used only to say what Harbor plays.
- **Permissions.** Bluetooth scan is declared `neverForLocation` and asked for only in the pairing
  sheet, with an explanation first. No location, storage, camera or microphone permissions.
  Recordings are written through MediaStore into Music/Harbor, which needs no permission for an
  app's own files (API 29+), and they record the emulator's output, not the microphone.
- **Foreground service.** A recording keeps the `mediaPlayback` service running when Harbor goes
  to the background (its notification says it is recording): the synth is still sounding, which
  fits the declared type.
- **Target API.** targetSdk 37.
- **Exported components.** Only the launcher activity (with a VIEW filter for packages and
  backups) and the MIDI device service, which is protected by `BIND_MIDI_DEVICE_SERVICE`.

## Decisions for the owner

- **Firmware names that use others' marks.** The catalog includes *FM-1 Doom* (FreeDM's free
  assets, but the Doom name) and *winampFM Synth* (Winamp's name and look), and the Hub adds
  *No Man Sky FM*. Harbor only lists and downloads them, but a trademark complaint against the
  listing would land on Harbor. Options: leave them, or hide a short list in Play builds.
- **Content rating.** Expect "crude humour" (*Fart Machine One*) and possibly occult themes
  (*NOIZ DEMONS*' sigils) in the questionnaire. Neither blocks approval; answer them honestly.
- **Store listing.** Use Harbor's own screenshots (the panel as drawn by Harbor), not M-VAVE's
  product photos. Describe it as "plays firmwares for the M-VAVE FM-1"; don't put "M-VAVE" or
  "FM-1" in the app title.

## Play Console to-do

1. Host `docs/privacy-policy.md` (e.g. GitHub Pages), fill in the contact address, and add the
   URL to the listing and the About screen link.
2. Data safety: no data collected, no data shared. Data is encrypted in transit (HTTPS), and
   users can delete it by uninstalling.
3. Foreground service declaration: type `mediaPlayback`, because the user can keep the synth
   sounding (playing from MIDI or another app) with Harbor in the background, and the service runs
   only while it plays. Google asks for a short video of this.
4. Content rating questionnaire (see above).
5. Target audience: 13+ or 18+, not designed for children.
6. App signing: a release keystore, and Play App Signing.
