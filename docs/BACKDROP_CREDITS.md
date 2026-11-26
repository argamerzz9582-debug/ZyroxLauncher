# Backdrop credits

Zyrox Launcher ships short Minecraft-theme video loops as Home and Loading screen backdrops.
You can change both in **Settings → Miscellaneous** (Home backdrop / Loading screen backdrop,
plus dim level and battery-saver pause).

| In-app name | File | Source | Creator | License |
|---|---|---|---|---|
| Snowy Peaks (default Home) | `res/raw/zyrox_backdrop_snow.mp4` | [Pixabay #168653 "Game, Play, Snow"](https://pixabay.com/videos/game-play-snow-mountain-winter-168653/) | Qkild | [Pixabay Content License](https://pixabay.com/service/license-summary/): free to use, no attribution required |
| Diamond Forge (default Loading) | `res/raw/zyrox_backdrop_diamond.mp4` | [Pixabay #99299 "Sword, Diamond"](https://pixabay.com/videos/sword-diamond-nice-hd-background-99299/) | harold14102009 | Pixabay Content License |

Minecraft is a trademark of Mojang AB. Zyrox Launcher is not affiliated with or endorsed by
Mojang or Microsoft.

## Encoding

Every clip is produced by [`tools/backdrops/build_backdrops.sh`](../tools/backdrops/build_backdrops.sh)
using the segment plan in [`tools/backdrops/plan.tsv`](../tools/backdrops/plan.tsv):

- 1920×1080, 30 fps, H.264 High@4.1, yuv420p, CRF 21 (≤ 3 Mbit/s), `+faststart`.
- The audio track is removed, so playback never takes audio focus from music or voice chat.
- Seamless loop: the last 1.2 s crossfades into the first frame, ending exactly on the frame
  before frame 0. `MediaPlayer` looping therefore never shows a jump.
- A first-frame WebP poster (`res/drawable-nodpi/bg_zyrox_backdrop_<id>.webp`) is shown
  instantly, before the decoder starts. It is also the still image used when animation is off,
  Battery Saver is on, or the device is thermally throttling.

### Adding a backdrop

1. Put the licensed source clip at `.backdrop-work/<id>.src.mp4`, or add a downloadable
   source to `SRC` in the script.
2. Add a line to `plan.tsv`: `<id>  <start_s>  <loop_s>  zyrox_backdrop_<id>  bg_zyrox_backdrop_<id>`.
3. Run `tools/backdrops/build_backdrops.sh encode`.
4. Add an entry to `ZyroxBackdrops.Backdrop` and to the `zyrox_backdrop_names` / `_values`
   arrays, and credit the source in this file.
