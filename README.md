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

## Updating from inside the app
Home > Update downloads the newest release and opens the Android installer. The first time,
Android asks you to allow "Install unknown apps" for TVStreamer: turn it on, go back and press Update again.
