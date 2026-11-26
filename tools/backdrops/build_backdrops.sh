#!/usr/bin/env bash
# Zyrox Launcher backdrop pipeline.
#
# Downloads the licensed Minecraft-theme source clips and turns them into launcher backdrops:
#   * 1080p30 H.264 High (yuv420p), no audio track, +faststart (moov before mdat)
#   * seamless loop: the tail crossfades into the head, so MediaPlayer looping shows no jump
#   * matching first-frame WebP poster so the screen never flashes black before decode starts
#
# Usage:
#   tools/backdrops/build_backdrops.sh preview   # contact sheets with timestamps -> tools/backdrops/preview/
#   tools/backdrops/build_backdrops.sh encode    # final clips + posters -> app_mojo/src/main/res/...
#
# A locally provided source at .backdrop-work/<key>.src.mp4 always takes precedence over downloading.
#
# The segment plan lives in tools/backdrops/plan.tsv. Requirements: ffmpeg (with libx264, libwebp, drawtext),
# yt-dlp, curl.
#
# Sources & licenses (see docs/BACKDROP_CREDITS.md):
#   spirit   https://www.youtube.com/watch?v=sVHqVfh0b2M  S.E.I.M  "free to use for edits and other purposes"
#   shaders  https://www.youtube.com/watch?v=zG8NzqeT0ic  Naybr    free to use WITH credit
#   diamond  https://pixabay.com/videos/id-99299/          Pixabay Content License
#   snow     https://pixabay.com/videos/id-168653/         Pixabay Content License
set -euo pipefail

MODE="${1:-preview}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORK="${WORK_DIR:-$ROOT/.backdrop-work}"
PLAN="$ROOT/tools/backdrops/plan.tsv"
RAW_DIR="$ROOT/app_mojo/src/main/res/raw"
POSTER_DIR="$ROOT/app_mojo/src/main/res/drawable-nodpi"
FONT="/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
UA="Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
mkdir -p "$WORK"

declare -A SRC=(
  [spirit]="yt:sVHqVfh0b2M"
  [shaders]="yt:zG8NzqeT0ic"
  [diamond]="url:https://cdn.pixabay.com/video/2021/11/29/99299-655758366_large.mp4"
  [snow]="url:https://cdn.pixabay.com/video/2023/06/24/168653-839542456_large.mp4"
)

fetch() {
  local key="$1" out="$WORK/$1.src.mp4"
  [[ -s "$out" ]] && { echo "$out"; return 0; }   # pre-placed local source (e.g. an uploaded clip)
  local spec="${SRC[$1]:-}"
  [[ -n "$spec" ]] || { echo "no source configured for '$key' (place it at $out)" >&2; return 1; }
  case "$spec" in
    yt:*)
      local id="${spec#yt:}" ok=0
      for client in "default" "tv_simply" "web_embedded" "tv,web_safari" "mweb" "android_vr"; do
        local extra=()
        [[ "$client" != "default" ]] && extra=(--extractor-args "youtube:player_client=$client")
        if yt-dlp --no-progress --no-playlist "${extra[@]}" \
             -f "bv*[height<=1080][vcodec^=avc1]/bv*[height<=1080]/bv*[height<=1440]/b" \
             --remux-video mp4 -o "$WORK/$key.dl.%(ext)s" "https://www.youtube.com/watch?v=$id" \
             > "$WORK/$key.ytlog" 2>&1; then
          ok=1; break
        fi
        echo "yt-dlp client=$client failed for $id: $(grep -m2 -i 'error' "$WORK/$key.ytlog" | cut -c1-220 | tr '\n' ' ')" >&2
        echo "  [$key client=$client] $(grep -m1 -i 'error' "$WORK/$key.ytlog" | cut -c1-90)" >> "$WORK/yt_errors.txt"
      done
      if [[ $ok == 0 && -n "${SOURCE_DIR:-}" && -s "$SOURCE_DIR/$key.mp4" ]]; then
        cp "$SOURCE_DIR/$key.mp4" "$out"; echo "$out"; return 0
      fi
      if [[ $ok == 0 ]]; then
        # Fallback: public Invidious / Piped mirrors that proxy the stream themselves.
        for inst in https://inv.nadeko.net https://yewtu.be https://invidious.nerdvpn.de https://invidious.f5.si; do
          u="$(curl -fsSL -m 20 -A "$UA" "$inst/api/v1/videos/$id?local=true" 2>/dev/null | python3 -c '
import json,sys
try:
    d=json.load(sys.stdin)
except Exception:
    sys.exit(0)
best=None
for f in d.get("adaptiveFormats",[]):
    t=f.get("type","")
    if t.startswith("video/mp4") and "avc1" in t:
        h=int((f.get("resolution") or "0p").rstrip("p") or 0)
        if h<=1080 and (best is None or h>best[0]): best=(h,f["url"])
print(best[1] if best else "")' 2>/dev/null)"
          if [[ -n "$u" ]]; then
            [[ "$u" == /* ]] && u="$inst$u"
            if curl -fsSL -m 600 -A "$UA" -o "$out" "$u" && [[ $(stat -c%s "$out") -gt 1000000 ]]; then
              echo "  [$key] fetched via $inst" >> "$WORK/yt_errors.txt"; ok=1; break
            fi
          fi
          echo "  [$key] mirror $inst failed" >> "$WORK/yt_errors.txt"
        done
        [[ $ok == 1 ]] && { echo "$out"; return 0; }
        for api in https://pipedapi.kavin.rocks https://pipedapi.adminforge.de; do
          u="$(curl -fsSL -m 20 -A "$UA" "$api/streams/$id" 2>/dev/null | python3 -c '
import json,sys
try:
    d=json.load(sys.stdin)
except Exception:
    sys.exit(0)
best=None
for f in d.get("videoStreams",[]):
    if f.get("videoOnly") and "avc1" in (f.get("codec") or ""):
        h=int(f.get("height") or 0)
        if h<=1080 and (best is None or h>best[0]): best=(h,f["url"])
print(best[1] if best else "")' 2>/dev/null)"
          if [[ -n "$u" ]] && curl -fsSL -m 600 -A "$UA" -o "$out" "$u" && [[ $(stat -c%s "$out") -gt 1000000 ]]; then
            echo "  [$key] fetched via $api" >> "$WORK/yt_errors.txt"; ok=1; break
          fi
          echo "  [$key] piped $api failed" >> "$WORK/yt_errors.txt"
        done
        [[ $ok == 1 ]] && { echo "$out"; return 0; }
        return 1
      fi
      mv "$(ls "$WORK/$key".dl.* | head -1)" "$out"
      ;;
    url:*)
      curl -fsSL -A "$UA" -e "https://pixabay.com/" --retry 3 -o "$out" "${spec#url:}"
      ;;
  esac
  echo "$out"
}

duration() { ffprobe -v error -show_entries format=duration -of csv=p=0 "$1"; }

if [[ "$MODE" == "preview" ]]; then
  OUT="$ROOT/tools/backdrops/preview"; mkdir -p "$OUT"; : > "$OUT/status.txt"; : > "$WORK/yt_errors.txt"
  for key in ${KEYS:-spirit shaders diamond snow}; do
    if ! src="$(fetch "$key")"; then echo "$key: DOWNLOAD FAILED" >> "$OUT/status.txt"; continue; fi
    dur="$(duration "$src")"
    info="$(ffprobe -v error -select_streams v:0 -show_entries stream=width,height,r_frame_rate,codec_name -of csv=p=0 "$src")"
    echo "$key: ok duration=${dur}s video=$info" >> "$OUT/status.txt"
    # aim for ~30 thumbnails per sheet
    step="$(python3 -c "import math;print(max(1,math.ceil(float('$dur')/30)))")"
    rows="$(python3 -c "import math;print(math.ceil(float('$dur')/$step/6))")"
    ffmpeg -nostdin -v error -y -i "$src" -vf "fps=1/$step,scale=320:-2,drawtext=fontfile=$FONT:text='%{pts\:hms}':x=6:y=6:fontsize=20:fontcolor=white:box=1:boxcolor=black@0.6,tile=6x$rows" \
      -frames:v 1 -q:v 4 "$OUT/$key.jpg"
  done
  cat "$WORK/yt_errors.txt" >> "$OUT/status.txt" || true
  cat "$OUT/status.txt"
  exit 0
fi

if [[ "$MODE" == "encode" ]]; then
  : > "$WORK/yt_errors.txt"
  # plan.tsv columns: key  start_seconds  loop_seconds  output_raw_name  poster_name(or -)
  grep -v '^\s*#' "$PLAN" | while IFS=$'\t' read -r key start len rawname poster; do
    [[ -z "${key:-}" ]] && continue
    src="$(fetch "$key")"
    fade=1.2
    total="$(python3 -c "print($len+$fade)")"
    offset="$(python3 -c "print($len-$fade)")"
    # transition ends exactly on the last frame (progress 1.0) -> last frame == frame preceding frame 0
    xdur="$(python3 -c "print(round($fade-1/30,5))")"
    out="$RAW_DIR/$rawname.mp4"
    echo "encoding $key [$start +${len}s] -> $out"
    # Seamless loop: body = [fade, len+fade), head = [0, fade). Crossfading the body's tail into the head
    # makes the last frame flow into frame 0 of the body (== source time start+fade).
    ffmpeg -nostdin -v error -y -ss "$start" -t "$total" -i "$src" -filter_complex "\
      [0:v]fps=30,scale=1920:1080:force_original_aspect_ratio=increase:flags=lanczos,crop=1920:1080,setsar=1,format=yuv420p,split[a][b];\
      [a]trim=start=$fade:end=$total,setpts=PTS-STARTPTS[body];\
      [b]trim=start=0:end=$fade,setpts=PTS-STARTPTS[head];\
      [body][head]xfade=transition=fade:duration=$xdur:offset=$offset,format=yuv420p[v]" \
      -map "[v]" -an -sn -dn -map_metadata -1 \
      -c:v libx264 -preset veryslow -profile:v high -level:v 4.1 -pix_fmt yuv420p \
      -crf 21 -maxrate 3000k -bufsize 6000k -g 60 -keyint_min 30 -bf 3 \
      -tune film -movflags +faststart "$out"
    if [[ "$poster" != "-" ]]; then
      ffmpeg -nostdin -v error -y -i "$out" -frames:v 1 -vf "scale=1280:-2:flags=lanczos" -c:v libwebp -quality 82 \
        "$POSTER_DIR/$poster.webp"
    fi
    ls -l "$out"
  done
  exit 0
fi

echo "unknown mode: $MODE" >&2; exit 2
