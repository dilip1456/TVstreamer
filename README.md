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
- Tap the middle to show/hide controls; tap the left or right side to seek 10s; hold to skip 15s.
- Swipe up/down on live TV to change channel.

## Updating from inside the app
Home > Update downloads the newest release and opens the Android installer. The first time,
Android asks you to allow "Install unknown apps" for TVStreamer: turn it on, go back and press Update again.
