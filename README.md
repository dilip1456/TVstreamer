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

## 1.54: sub-categories, view states, hide whole groups, compact menus

**Live TV**
- **View states:** Only active (the default), Only hidden, or All channels (hidden ones dimmed with a Hidden tag).
  - Pick one from the Menu popup: Menu on a channel, Menu on the left nav, or ⋮ on a phone.
  - Select channels… moved into the same menu.
- **Left nav:** now only Search, Mode, Languages and the category tree. All channels and Select are no longer there.
- **Sub-categories by topic:** every type gets them (never by language; that stays a separate filter):
  - Sports: Cricket, Tennis, Football, American Football, Basketball, Baseball, Hockey, Golf, Horse Racing, Motorsport, Combat, Rugby, Athletics & More
  - News: Local News, National, International, Business, Weather, Politics
  - Movies: Premium, Action, Comedy & Romance, Horror, Classic, Family, Indie & World
  - Entertainment: Networks, Drama & Series, Comedy, Reality, Talk & Game shows, Shopping
  - Kids: Preschool, Cartoons, Education, Teens & Family
  - Music: Pop & Hits, Film & Folk songs, Hip Hop & R&B, Rock, Dance, Country, Classical & Jazz, Radio
  - Documentary & Lifestyle: Nature, History, Science & Tech, Crime, Cooking & Food, Travel, Home & Fashion
  - Religious: Christian, Islamic, Hindu, Sikh, Jain & Buddhist
  - Everything else: Other
- **Sports regions:** Sports also lists regions (Australia, New Zealand, Caribbean, UK & Ireland, USA, Canada, India, Pakistan, …), so unwanted regional sports channels can be hidden in one go.
- **Category tree:** the left nav is an expandable tree with counts. Sub-categories larger than 60 channels are split into sections by region or portal category.
- **Hide whole groups:** hold OK (or long-press) on a category, sub-category or region to hide it. A bar with Undo appears for 5 seconds. Hidden groups show dimmed in Only hidden / All channels, and holding again shows them. They are also listed in Settings > Hidden and sync like hidden channels.
- **Top menu:** the Home / Movies / Series / Live TV tabs now show on Live TV (TV). Up from the list reaches them, and Back in the list goes to them first, so Back no longer leaves Live TV.

**Menus and panels**
- **Compact menus:** small rounded see-through cards on the right with tight rows and small text. Move to top was removed from the channel menu.
- **Live player side panels:** the channels (Left) and schedule (Right) panels are narrower rounded see-through cards with tighter rows and smaller text.


### Subtitles (1.54)
- Downloaded subtitles are saved on the device (per title / episode / language) and re-used: replays and later sessions never download again. The language picked for a title is remembered and attached automatically next time.
- Sources, in order: saved copy → OpenSubtitles.com (your API key; add your free account login for 20 downloads/day instead of 5) → SubDL (your own free key: 2,000 searches / 50 downloads a day). When a source's daily limit is reached the player says so and tries the next one; that source is skipped until its reset time.
- All keys go in Settings > API keys; none are built in.

## 1.53: clean live player, faster guide, simpler Settings

**Live TV player**
- No player controls for live channels: no seek bar, play / pause or arrows. Just the picture. Movies and series keep the normal controls.
- Up / Down change channel at once.
- OK shows a slim bar along the bottom: number and name, what's on now with a progress line and times, and what's next. It hides after 5 seconds. The old top-left box is gone.
- Left: the channels of the playing channel's category (Up / Down browse, OK plays).
- Right: this channel's schedule as a list (now, next, later). OK on a later show sets a reminder.
- Back closes the top overlay first, then returns to the list.
- On a phone, tap shows the bar with Channels, Schedule and ⋮.
- A dead channel you zapped onto shows a short note at the bottom and is skipped in the same direction (at most 5 in a row).

**Hide and unhide quickly**
- Select mode: Select in the left nav (TV), long-press a channel (phone), or ⋮ > Select channels. OK / tap checks channels. A slim bar at the bottom shows the count and Hide, Unhide, Select section and Cancel. In a custom mode these are Remove from mode / Add to mode. On TV, Menu jumps to the bar. Back leaves Select mode first.
- Double OK on the remote (two presses within 0.3 s) hides a channel, or unhides it in All channels. A short "Hidden" / "Unhidden" toast appears. A single OK plays after that short window.
- In the live player a single OK only shows the info bar. Double OK hides or unhides the playing channel; it keeps playing, marked Hidden.

**Back to the same list**
- Back from a channel returns to the same Live TV view: same mode, languages, All channels on or off, scroll position, and focus on the playing channel. This is also remembered after a restart.
- Cause (1.52): All channels was a screen-local switch, lost when the player opened. Back also always re-scrolled the list.

**Live links**
- More ways to ask the portal for a live link: no-temp-link direct cmds first, every create_link form, the channel's alternate cmds, and placeholders by channel id and by the cmds ch_id.
- After a failure the app reads the first bytes of the stream (following redirects) to tell HLS from TS from an HTML error page. It then replays the stream in the right format, or signs in again for 401 / 403, or tries the next way.
- Settings > Troubleshooting > Live link diagnostics and Channel info show the last attempt per channel: which way was used, HTTP status, real format and a masked link. Host kind only; ids, user names, passwords and tokens are never shown.

**Guide (EPG)**
- The guide is saved on the device and shows at once. It refreshes in the background.
- Entries are kept until their shows run out (at most 12 h), not 15 minutes.
- Guide data loads for the visible rows plus 6 ahead, and for the channels next to the one playing.
- The schedule list loads today's full schedule (get_simple_data_table, at most 3 pages, else a longer get_short_epg) when a channel starts.
- Still one request at a time through the portal limiter.

**Look and navigation**
- The top tab bar stays pinned while the page scrolls beneath it.
- Settings is a Netflix-style split: short sections on the left (Account, Portal, Content, Player, Languages, API keys, Hidden, Troubleshooting) and big one-line rows on the right. There are no paragraphs; API key notes moved to Learn more. Back goes from a section's rows up to the section list.

## 1.52: Live TV rework, Netflix-style look

**Live TV**
- One clean list, grouped by the portal's type and category only (no A-Z letter groups, no grid / icon view). Each row shows the channel number, logo, a cleaned name ("|UK| BBC ONE FHD" -> "BBC One"), what is on now with a progress bar, and what is next.
- "US Local" categories are local news: they sit under News, labelled "Local News", with names like "WABC New York".
- TV: a left menu (icons; labels open while it has focus, it closes when you move right) with Search, Mode, All channels, Languages and the sections. Left on a channel goes to its section in the menu.
- Modes replace My Channels folders and Recently watched. "Default" lists every channel that is not hidden. Custom modes (create, rename, delete) list only their channels. A new mode starts either with everything hidden or with the channels listed now. Old folders, including Favorites, become modes. Modes sync with Google like folders did.
- All channels: hidden channels are listed dimmed with a "Hidden" mark. Hold OK (or long-press) to show or hide one.
- Languages: pick one or more (Menu on the left menu, its Languages entry, or ⋮ on a phone). Other languages are hidden everywhere in Live TV. The choice is remembered and synced.
- The guide (now / next) loads only for the channels on screen, one gentle request at a time, and is cached for 15 minutes.
- Channel menu (Menu key): Play, Hide / show, Add to a mode, Move to top of mode, Guide, Remind me, Rename, Channel info, and Close last.
- Player: Up / Down change channel at any time, even with the info bar showing. Left opens the channel panel: Left / Right step through a channel's schedule, and OK on an upcoming show sets a reminder (a message at its start time). Back to the list lands on the channel that was playing.
- A link that fails shows a small message at the bottom instead of a popup, so you can keep zapping. OK retries.
- Channels that didn't load: live links now get the same checks as movies. A "localhost" answer is pointed at the portal. An empty stream or token signs in again and asks once more. Channels without a cmd use the portal's fallback. Streams that are HLS or TS without a telling URL are retried in the other format.

**Everywhere**
- One Back closes only the top popup, menu or panel, and focus returns to the item that opened it.
- TV text fields open the keyboard only on OK, never just because focus moved onto them.
- Movies / Series grids: no more jumping back and repeating after about 15 titles. Cards keep stable keys, new pages are appended after what is on screen, and a portal that repeats a page stops paging.
- Grids: Right at the end of a row goes to the next row. Left at the start of a row goes to the menu on the left, or (when there is none) to the end of the previous row.
- Movie / series menu: Play or Episodes, Details, My List, Mark watched / unwatched, Fix match, Versions, Hide this title (show it again in Settings > Troubleshooting), Close.
- Look: a Netflix-style top menu on TV (profile left, text tabs with a soft light pill on focus; Settings and Update moved to the profile menu), a rounded hero card, rounded posters with a subtle animated scale and soft gold ring on focus, pill buttons, screens that cross-fade, and popups that fade in. No blur or shadows, to keep Fire TV smooth.

## 1.51: cleaner screens

- Hidden rows, categories and channels are restored only in Settings > Hidden (no "Show hidden" buttons elsewhere).
- Live TV: compact toolbar (🔍 · Select · A-Z · grid/list); the language / country chips are remembered across groups
  (the separate language dialog is gone).
- Home: no slide arrows, version label or load buttons (titles load as you scroll; Stop / Resume / Refresh in Settings).
- Details: Fix match moved to the Menu / ⋮ options; fewer hint lines.
- Player: shorter titles ("3 / 120"), no duplicate LIVE, "Move" is in ⋮ options.
- Settings: grouped sections (Portal, Content, Player, API keys, Hidden, Troubleshooting) and one "Save keys and test".

## 1.50: gentler portal, faster series, new Live TV

- **Portal requests**: only a real rate limit (HTTP 429, or 503 with Retry-After) makes the app wait. An expired
  token (401 / 403) signs in again once and retries; a single server error (5xx) no longer pauses everything. Only
  repeated refusals / server errors pause briefly (30 s), with a message that says what happened. A failed stream link
  no longer fires every other link strategy after a pause, and zapping on cancels the old channel's requests.
- **Series**: the season list shows at once (saved per series), and only the season you look at is fetched (saved per
  season, at most 2 season requests queued). Next episode uses the saved seasons instead of re-reading every season.
- **Live TV**: groups first (My Channels folders, Recently watched, News, Sports, Movies, Music, Kids ...), language and
  country filters, logo tiles with number badges, the channel playing now marked, sticky headers and A-Z jump.
  TV: left group rail; phone: group chips.
- **Channel names**: the best name from the portal's fields; "123 | ..." prefixes removed; number-only names read
  "Channel 123 · Group"; the number is a badge.
- **Details**: Versions show which one is selected (✓); the default is the one played last; OK / tap plays a version.
- **Home**: hide any row from its ⋮ (TV: Menu on the row) > Hide this category; restore in Settings or Categories >
  Show hidden. Synced with Google sync.
- **Phone search**: a large full-width field; scope chips and mic / AI on their own row.

## 1.49: player controls, next episode, subtitles, My Channels
- TV / remote: one standard control bar under the video (seek bar; previous, −10 s, play/pause, +10 s, next episode; audio, subtitles, picture, more). No side panel on TV. Back hides the bar, Back again leaves. The movable side bar stays on phones.
- Series: in the last 10 seconds of an episode a "Next episode in 10s" card offers Play now / Cancel; the next episode (or the next season's first) starts automatically. Turn it off in Settings > Player.
- Audio and subtitle choices are remembered (for everything, and per show while watching a series) and synced with Google sync.
- Subtitles: pick embedded tracks, change the text size, or download subtitles from OpenSubtitles.com (Subtitles > Download subtitles). Downloading needs your own free OpenSubtitles API key in Settings (opensubtitles.com/en/consumers); nothing is built in. Without an account OpenSubtitles allows about 5 downloads a day.
- My Channels (top of Live TV, in Normal and Favorites mode): your own channel folders. Menu (or long-press) on a channel > Add to favorites opens a folder picker: tick one or more folders, or make a new one. A favorite channel is one that is in at least one folder; Remove from favorites takes it out of all of them. Use Select to tick several channels and add them to folders in one go. Menu on a folder chip: rename, move, delete. Menu on a channel inside a folder: move earlier / later, remove from that folder.
- Rename channel (channel menu): your own name is shown in lists, folders, the player and search (search finds the portal name too); Reset name brings the portal name back. Folders and names are synced with Google sync.

## Remote
- Select: open or play; while watching, shows the controls
- Menu (three lines): favorite a channel or movie, hide a category; while watching: player options
- Up/Down while watching live (controls hidden): next or previous channel
- Left/Right while watching a movie (controls hidden): seek 10 seconds
- Play/Pause, Fast-forward, Rewind and Channel up/down keys work at any time
- Back: hides the controls first, then leaves the player
- Search: the on-screen keyboard supports voice dictation
- Voice (1.44): the remote's microphone / Search key (Fire TV, Android TV) opens Search and starts listening;
  system voice-search requests aimed at TVStreamer open its Search with the words filled in (needs microphone permission)

## Phone
- The player turns to landscape full screen.
- Single tap shows/hides the controls (it never seeks).
- Double-tap the left or right half to go back / forward 10s; keep double-tapping to add more (-20s, +30s, ...).
- Press and hold on a movie or episode to play at 2x while held.
- Swipe up/down on live TV to change channel.

## Catalog (Home, Movies, Series)
- Hero banner on top (1.44: the last movie or episode you watched, with "Resume · time"; a featured title only when
  there is no watch history yet), then Continue watching, My List and one row per category (posters load as you scroll).
- OK / tap a poster for details (plot, year, rating, seasons and episodes). Menu / long-press: My List, move row up/down, hide category.
- "See all" at the end of a row opens the whole category as a grid.
- Categories: browse every category with a sample poster, filter by name, move up/down, hide/unhide.
- Names are tidied for display only ("EN| Action" shows as "Action · EN").

## Languages, versions and sorting (1.39)
- Movies and Series are separate tabs. Each has a Languages picker (with title counts, Select all / none).
  The language is read per title, then from its category ("HINDI 4K", "IN| BOLLYWOOD", "|EN|", "[Tamil]", "Pathaan Hindi").
  A dubbed title counts as the language it is dubbed in ("HOLLYWOOD HINDI DUBBED" is Hindi). Short codes such as IN or IT
  only count as tags ("IN| …", "[IT]"), so "MOVIES IN 4K" is not Hindi. Dub, CAM, 4K, HD and so on are versions, not
  languages; anything with no recognisable language is listed under "Other".
- For each chosen language: Latest added, Top rated, Newest releases, then rows by genre.
  Languages you did not pick are hidden from the rows but are still found by Search and in Categories ("Show all languages").
- The same title and year in several qualities (CAM / 4K / Dub) shows once. The detail screen has a Versions choice
  (best first, CAM last).
- Sort on grids: Recently added (default), Release year, Rating (IMDb first), A-Z. Titles without that info go last.
- See "Library and refresh" below for how the catalog is loaded.
- Continue watching: Menu / long-press on an episode gives Resume, Start over, See all episodes and Remove.

## Library loading, gentle on your provider (1.45)
The app loads titles the way a person browsing would, so the IPTV provider does not see unusual traffic:
- Only the categories of the languages you picked on Movies / Series are loaded (the language comes from the category
  name and the titles found in it). Unselected languages are never loaded; "Other" only when you pick it.
- First the newest page of each of those categories. Older pages load only when you need them: reaching the end of a
  row or grid, sorting by year or rating, opening a category, or when a language shows few titles.
- One request at a time, a short random pause between pages, at most about 2 portal requests a second, and at most
  150 pages per app session. If the portal answers "too many requests", "forbidden" or a server error, the app stops
  and waits (1 min, then longer each time) and tells you; saved titles keep working.
- Everything loaded is saved on the device and never downloaded again. Once a day the app checks only the newest page
  of each category for new titles. After several failed streams it checks early, at most twice a day.
- "■ Stop loading" on the Movies / Series tabs and in Settings stops all background loading ("▶ Resume" continues).
- Settings > Library: titles saved, last refresh, and "Refresh library" (asks to confirm; once every 10 minutes at most).
- The app sends the same headers as a MAG250 box on every request. Stream links are only requested when you press Play.

## AI suggestions and voice assistant (1.44, optional)
Settings > AI suggestions & voice assistant: paste a Google Gemini API key (free at aistudio.google.com/apikey) and press "Save Gemini key and test".
The key is stored encrypted on the device (and synced to your other devices with Google sync on), never in this repo.
With a key:
- Home, Movies and Series get a "✨ Suggested for you" row based on what you watch, chosen from your own portal library.
- In Search, "✨ Ask AI" (or the 🎤 button / remote mic) understands requests like "a funny Hindi movie from the 90s" or
  "something like Dark" and shows matching titles from your library.
Titles from your history, favourites and library (never your portal login) are sent to Google Gemini for this.
Without a key everything else works as before.

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
An own Google client entered under Advanced is never synced.

Where it is stored: one file (`tvstreamer-sync.json`) in the hidden app-data folder of *your own* Google Drive. Only
TVstreamer can read it; it does not appear in your Drive file list. (If Google refuses the
app-folder permission, the same file is kept in My Drive instead; see "If sign-in fails" below.) Sign-in tokens are stored
encrypted on the device and never logged. "Sign out" removes them from the device; "Delete cloud data" removes the file.

How it syncs: on sign-in, when the app starts or comes back, about 10 s after a change, and with "Sync now". Every
item has its own change time, so the TV and phone can both make changes; the newest change of each item wins and
removals are remembered. Offline changes are kept and sent later. On a device's first sign-in, the cloud copy wins
any conflict and that device's extra items are added.

### Signing in (1.46: nothing to set up)
Official builds have a Google sign-in client built in, so every TV and phone only needs the Google code step:
1. Settings > Google account & sync > **Sign in with Google**.
2. The screen shows a code and a QR code. On your phone open https://www.google.com/device (or scan the QR), enter
   the code and pick your account. If Google warns "app isn't verified", choose Continue. **Tick the Google Drive box**
   ("See, create, and delete its own configuration data in your Google Drive", or "…files that you use with this
   app") and press Continue. The app syncs at once.

While the app's Google project is in "Testing", only Gmail addresses added as test users in that project can sign in.

How the built-in client gets into the app: GitHub Actions passes the repository secrets `GOOGLE_CLIENT_ID` and
`GOOGLE_CLIENT_SECRET` to the build as environment variables, and the build stores them as `BuildConfig` fields. They
are never in the repository. A local build without them (`GOOGLE_CLIENT_ID=… GOOGLE_CLIENT_SECRET=… gradle
assembleDebug`, or the same names in `~/.gradle/gradle.properties`) has no built-in client; it still builds and
runs, and then asks for your own client under Advanced. Note: a client secret inside an installed app can always be
read out of the APK; Google treats TV/installed-app secrets as not confidential, which is why this client type is used.

### Advanced: your own Google client (optional)
Settings > Google account & sync > **Advanced: use my own Google client…** overrides the built-in client on that
device (leave it empty, or press "Use built-in client", to go back). An existing sign-in keeps using the client it
was made with until you sign out and in again; sign-ins made before 1.46 keep using the client saved on the device.
To make your own client (free, about 5 minutes):
1. On a computer open https://console.cloud.google.com/ and sign in with the Google account you will use.
2. Top bar > project picker > **New project**, name it e.g. `TVstreamer`, Create, and select it.
3. **APIs & Services > Library**, search **Google Drive API**, open it, click **Enable**.
4. **APIs & Services > OAuth consent screen** (Google Auth Platform > Branding / Audience): User type **External**,
   app name `TVstreamer`, your email as support and developer contact. Save.
5. **Audience > Test users > Add users**: add your own Gmail address. (Leave the app in "Testing".)
6. **Data access > Add or remove scopes**: add `.../auth/drive.appdata` and `.../auth/drive.file` (plus openid,
   email, profile). Save.
7. **APIs & Services > Credentials > Create credentials > OAuth client ID**, Application type
   **TVs and Limited Input devices**, name `TVstreamer TV`, Create.
8. Copy the **Client ID** and **Client secret** into Advanced on each device and press Save client, then sign in.

Note: while the consent screen is in "Testing", Google may expire the sign-in after 7 days; the app then says
"Sign in again". Switching the consent screen to "In production" avoids that; Google may then show an
"unverified app" warning, which is fine for your own personal use.

### If sign-in fails (1.42)
The Google account & sync screen now shows Google's own error words, what to do, and a link to the page that fixes
it, plus a Troubleshooting checklist at the bottom. Common causes:
- **"Google would not offer the Drive … permission" (`invalid_scope`)**: Google's TV sign-in sometimes refuses the
  hidden app-folder permission (`drive.appdata`) for a client even though it is on Google's allowed list. From 1.42
  the app then asks for `drive.file` instead and keeps one file, `tvstreamer-sync.json`, in your normal My Drive
  (the app can only see files it made). If both are refused, check that the client type is "TVs and Limited Input
  devices", the Drive API is enabled, and both scopes are under Data access.
- **Drive box left unticked**: Google signs you in but without Drive. The app explains this and shows a new code;
  sign in again and tick the box.
- **`access_denied`**: Cancel was pressed, or your Gmail is not on Audience > Test users.
- **"Google Drive API is turned off"**: open the link shown, press Enable, wait a minute, press Sync now.
- **`invalid_client`**: with your own client, the Client ID/secret is mistyped or the client is not the TV type;
  with the built-in client, update the app.

## TV speed and focus (1.47)
- **Faster lists:** Home / Movies / Series rows are worked out in the background and remembered per tab, so a tab you
  return to shows at once; title parsing is cheaper.
- **Live TV sorting:** channels are sorted by type and language once per channel list (in the background, saved on the
  device). Channels appear while sorting runs, with progress, **Cancel**, **Try again** and **Original categories**;
  it never spins forever (stops with a message after 45 s).
- **Hero banner:** starts with the last thing you played, then rotates every 7 s through recently played titles and My
  List (each once, with Resume). A featured title is used only when there is neither. Pressing a key in the banner pauses
  it; ‹ › step by hand.
- **TV menu:** no floating Search button on TV (it trapped focus at the right end of the menu). Search is in the top
  menu and on Live TV; Left / Right stop at the menu ends; Back inside a page returns to the top menu.

## TV launcher icon and banner (1.46)
Android TV / Google TV / Fire TV show the 320x180 black-and-gold banner (`drawable-xhdpi/tv_banner_gold.png`); phones
and the TV app grid use the adaptive black-and-gold icon (`mipmap-anydpi-v26`, PNG fallbacks for older Android). The
banner, icon and round icon are set on both `<application>` and the launcher activity (Fire TV reads the activity).
TV launchers keep a cached copy of an app's icon: if the old blue icon still shows after updating, restart the TV
(Fire TV: Settings > My Fire TV > Restart), or uninstall and reinstall the app.

## Keyboard (1.43)
There are no "Hide keyboard" buttons any more. Close the on-screen keyboard with the remote's or phone's Back key, or
press Done / Search on the keyboard (Done also moves to the next item, so the D-pad never gets stuck).

## Profile, account menu and theme (1.41)

- **Phone:** a floating black bar at the bottom with Home, Movies, Series, Live TV and Profile (the selected tab is gold). It hides in the player. The search button sits above it.
- **TV:** the top bar stays the same and is still moved with the D-pad. Your avatar is at the end of the tab row.
- **Phone or TV is decided by the device type** (Android TV / Fire TV), not by screen size.
- **Avatar:** after Google sign-in, the bar shows your Google photo. Without a photo it shows gold initials, and when you're not signed in it shows a person icon.
- **Account menu** (tap Profile or the avatar): your name and email, the theme (Dark / Light / System), Settings, Preferences, Sync now, Check for update, and Sign out (or Sign in).
- **Theme:** Dark by default. It is saved and synced to your other devices. The player always stays dark.
- **Preferences page:** account and sync, theme, languages, categories, remote buttons, movie info keys, picture size, update check and version.

## Updating from inside the app
Home > Update downloads the newest release and opens the Android installer. The first time,
Android asks you to allow "Install unknown apps" for TVStreamer: turn it on, go back and press Update again.
