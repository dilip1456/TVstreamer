# TVStreamer

Android TV client for Stalker/MAG portals, built for Fire Stick.
No portal, MAC or password is stored in this repo. Enter them in the app under Settings.


## 1.57
- Clear watch history: Settings > Troubleshooting > Clear watch history now expands to Last 24 hours / All history / Cancel.
- Resume entries store a timestamp. Entries without a timestamp are treated as older than 24 hours.

## 1.56
- Compact lists with one type scale (title / header / row / secondary / caption) in Live TV, menus, panels and settings rows.
- Live TV rows: number, logo, clean name and a small language tag. The schedule is shown only in the player and channel menu.
- Collapsible sections: OK on a section header (chevron + count) or a sub-category in the left tree folds it. The state is saved.
- Clean names: language and country words, quality tags and numbering are removed ("IN | Star Plus Hindi" becomes "Star Plus").
- TV: section headers can take focus, Channel/Page up-down jumps between sections, and focus is restored to a header when its channel is folded.

## 1.55
- Focus stays inside every open menu, popup, dialog, side panel and the search overlay (shared focus trap in PopupFrame): Up on the first item no longer jumps to the tabs or list behind.
- Back from Search returns focus to the channel / poster focused before Search (Live TV scrolls to it).
- Live player Left panel lists the playing channel's Live TV section (same tree, names and order as the list, e.g. "Sports › Cricket"), not the portal's language group.
- Movies › By Actor & Collections: actor / star / collection channels ("English Actors", "Actors | Brad Pitt", "Tom Cruise Movies") go to Movies, never Music. Loose words (hits, gold, love, id, ram, top, now) no longer mis-file channels.
