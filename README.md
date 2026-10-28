# TVStreamer

Android TV client for Stalker/MAG portals, built for Fire Stick.
No portal, MAC or password is stored in this repo. Enter them in the app under Settings.

## Install on Fire Stick
1. Push to main. GitHub Actions builds and publishes tvstreamer.apk to Releases.
2. On the Fire Stick install the Downloader app, enable "Install unknown apps" for it.
3. In Downloader open: https://github.com/<user>/<repo>/releases/latest/download/tvstreamer.apk
4. To update later, run the same URL again.

## Player (1.36)
On-screen controls for remote and touch: play/pause, seek bar with time, -10s / +10s,
previous/next channel or episode, audio track, subtitles, Fit / Fill / Zoom, favourite, back.
Controls hide after 5 seconds. If a stream fails you get the reason on screen with Retry / Next / Back.
The next episode plays automatically.

## Remote
- Select: open or play; while watching, shows the controls
- Menu (three lines): favorite a channel or movie, hide a category; while watching: player options
- Up/Down while watching live (controls hidden): next or previous channel
- Left/Right while watching a movie (controls hidden): seek 10 seconds
- Play/Pause, Fast-forward, Rewind and Channel up/down keys work at any time
- Back: hides the controls first, then leaves the player
- Search: the on-screen keyboard supports voice dictation

## Phone
- The player turns to landscape full screen.
- Single tap shows/hides the controls (it never seeks).
- Double-tap the left or right half to go back / forward 10s; keep double-tapping to add more (-20s, +30s, ...).
- Press and hold on a movie or episode to play at 2x while held.
- Swipe up/down on live TV to change channel.

## Catalog (Home, Movies, Series)
- Hero banner on top, then Continue watching, My List and one row per category (posters load as you scroll).
- OK / tap a poster for details (plot, year, rating, seasons and episodes). Menu / long-press: My List, move row up/down, hide category.
- "See all" at the end of a row opens the whole category as a grid.
- Categories: browse every category with a sample poster, filter by name, move up/down, hide/unhide.
- Names are tidied for display only ("EN| Action" shows as "Action · EN").

## Languages, versions and sorting (1.39)
- Movies and Series are separate tabs. Each has a Languages picker (with category counts, Select all / none).
  The language is read from category and title names ("HINDI 4K", "|EN|", "[Tamil]", "Telugu Dubbed").
  Dub, CAM, 4K, HD and so on are versions, not languages; names with no language go under "Other".
- For each chosen language: Latest added, Top rated, Newest releases, then rows by genre.
  Languages you did not pick are hidden from the rows but are still found by Search and in Categories ("Show all languages").
- The same title and year in several qualities (CAM / 4K / Dub) shows once. The detail screen has a Versions choice
  (best first, CAM last).
- Sort on grids: Recently added (default), Release year, Rating (IMDb first), A-Z. Titles without that info go last.
- The library loads in the background, is kept on the device for 12 hours and has a Refresh button.
- Continue watching: Menu / long-press on an episode gives Resume, Start over, See all episodes and Remove.

## Movie info from TMDB / OMDb (optional)
Settings > Movie & series info: paste a TMDB API key (v3 key or v4 read token, free at themoviedb.org)
and optionally an OMDb key (omdbapi.com) for IMDb ratings. Keys are kept encrypted on the device, never in this repo.
Without keys the portal's own posters and info are used.
If a title is matched to the wrong film, open it and press Menu (or long-press a button) > Fix match.

## Remote buttons (1.39)
Settings > Remote buttons: press a button to learn it, then choose what a short press and a long press do
(My List, play/pause, seek, next/previous, last channel, subtitles, audio, aspect, go to Live / Movies / Series / Search...).
Reset restores the defaults. Default: holding Menu adds or removes the focused or playing title from My List.
The arrows, OK, Back, Home, volume and voice keys are not remappable.
Fire TV app buttons (Netflix, Prime Video, Hulu...) are handled by Fire TV itself and never reach the app.
On supported remotes you can point a shortcut button at TVStreamer in Fire TV Settings > Controllers & Bluetooth Devices >
Amazon Fire TV Remotes > Remote button shortcuts.

## Live TV (1.40)
- Two modes at the top, remembered: **All channels** and **★ Favorites**.
- All channels: grouped by type (News, Sports, Entertainment, Movies, Music, Kids, Documentary & Lifestyle, Religious,
  Regional / General, Other) with a language row, or "Original categories" for the portal's own folders.
  "Languages" picks which languages to show (from category and channel names).
- Favorites: "Grouped" shows only the groups that contain favourites (by type or original category), each with just the
  favourite channels; "Single list" shows them all in one list, in your order (Menu > Move up / down) or A-Z.
- Channel up/down in the player steps through the list you started from.
- Hidden channels: "Show hidden in this group" lists them so you can unhide.

## Search (1.40)
The gold search button (bottom-right; also the Search or blue remote key) opens search over any screen.
Choose All / Movies / TV Series / Live. It searches every category and language on the portal. OK on a result opens it;
Back closes search.

## Series (1.40)
Seasons and episodes are shown without the show's name ("Season 1", "Episode 2" or the real episode title).
Opening a show you started goes to the season and episode you were on (the next one if you finished it).

## Movie info keys (1.40)
Settings > Movie & series info has "Get a TMDB key" and "Get an OMDb key" buttons next to the key boxes. They open
https://www.themoviedb.org/settings/api and https://www.omdbapi.com/apikey.aspx in the browser. If the device has no
browser (common on Fire TV), the address is shown on screen so you can open it on a phone or computer.

## Google sign-in and sync (1.40)
Settings > Google account & sync. Sign in once on the TV and once on the phone with the same Google account; favourites,
hidden categories and channels, category order, languages, sorting, Live TV mode settings, remote buttons, Continue
watching / last played episodes, TMDB "Fix match" choices and player settings stay the same on both.
"Also sync portal login and API keys" is ON by default: portal URL, MAC, serial, locked-section password and TMDB/OMDb
keys are synced too, so a new device is ready right after sign-in. Turn it off to keep them on each device only.
The Google client ID / secret are never synced (they are needed to sign in).

Where it is stored: one file (`tvstreamer-sync.json`) in the hidden app-data folder of *your own* Google Drive. Only
TVstreamer (your OAuth client) can read it; it does not appear in your Drive file list. Sign-in tokens are stored
encrypted on the device and never logged. "Sign out" removes them from the device; "Delete cloud data" removes the file.

How it syncs: on sign-in, when the app starts or comes back, about 10 s after a change, and with "Sync now". Every
item has its own change time, so the TV and phone can both make changes; the newest change of each item wins and
removals are remembered. Offline changes are kept and sent later. On a device's first sign-in, the cloud copy wins
any conflict and that device's extra items are added.

### One-time Google Cloud setup (free, about 5 minutes)
Google sign-in on TVs needs an "OAuth client" that belongs to you (no client ID is built into the app).
1. On a computer open https://console.cloud.google.com/ and sign in with the Google account you will use.
2. Top bar > project picker > **New project**, name it e.g. `TVstreamer`, Create, and select it.
3. **APIs & Services > Library**, search **Google Drive API**, open it, click **Enable**.
4. **APIs & Services > OAuth consent screen** (Google Auth Platform > Branding / Audience): User type **External**,
   app name `TVstreamer`, your email as support and developer contact. Save.
5. **Audience > Test users > Add users**: add your own Gmail address. (Leave the app in "Testing".)
6. **Data access > Add or remove scopes**: add `.../auth/drive.appdata` (plus openid, email, profile). Save.
7. **APIs & Services > Credentials > Create credentials > OAuth client ID**, Application type
   **TVs and Limited Input devices**, name `TVstreamer TV`, Create.
8. Copy the **Client ID** and **Client secret** into the app: Profile/Settings > Google account & sync. Enter them
   on the TV and on the phone (or type them on one device and copy them across).
9. Press **Sign in with Google**. The TV shows a code and a QR code: on your phone open https://www.google.com/device
   (or scan the QR), enter the code, pick your account, and allow. Google warns "app isn't verified": choose
   Continue (it is your own app). The app syncs at once.

Note: while the consent screen is in "Testing", Google may expire the sign-in after 7 days; the app then says
"Sign in again". Switching the consent screen to "In production" avoids that; Google may then show an
"unverified app" warning, which is fine for your own personal use.

## Updating from inside the app
Home > Update downloads the newest release and opens the Android installer. The first time,
Android asks you to allow "Install unknown apps" for TVStreamer: turn it on, go back and press Update again.
