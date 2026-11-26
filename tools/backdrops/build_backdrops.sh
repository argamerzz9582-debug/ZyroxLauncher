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
  local key="$1" spec="${SRC[$1]}" out="$WORK/$1.src.mp4"
  [[ -s "$out" ]] && { echo "$out"; return 0; }
  case "$spec" in
    yt:*)
      local id="${spec#yt:}" ok=0
      for client in "default" "tv,web_safari" "ios" "mweb" "android"; do
        local extra=()
        [[ "$client" != "default" ]] && extra=(--extractor-args "youtube:player_client=$client")
        if yt-dlp -q --no-warnings --no-playlist "${extra[@]}" \
             -f "bv*[height<=1080][vcodec^=avc1]/bv*[height<=1080]/bv*[height<=1440]/b" \
             --remux-video mp4 -o "$WORK/$key.dl.%(ext)s" "https://www.youtube.com/watch?v=$id" >&2; then
          ok=1; break
        fi
        echo "yt-dlp client=$client failed for $id" >&2
      done
      [[ $ok == 1 ]] || return 1
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
  OUT="$ROOT/tools/backdrops/preview"; mkdir -p "$OUT"; : > "$OUT/status.txt"
  for key in spirit shaders diamond snow; do
    if ! src="$(fetch "$key")"; then echo "$key: DOWNLOAD FAILED" >> "$OUT/status.txt"; continue; fi
    dur="$(duration "$src")"
    info="$(ffprobe -v error -select_streams v:0 -show_entries stream=width,height,r_frame_rate,codec_name -of csv=p=0 "$src")"
    echo "$key: ok duration=${dur}s video=$info" >> "$OUT/status.txt"
    # aim for ~30 thumbnails per sheet
    step="$(python3 -c "import math;print(max(1,math.ceil(float('$dur')/30)))")"
    rows="$(python3 -c "import math;print(math.ceil(float('$dur')/$step/6))")"
    ffmpeg -v error -y -i "$src" -vf "fps=1/$step,scale=320:-2,drawtext=fontfile=$FONT:text='%{pts\:hms}':x=6:y=6:fontsize=20:fontcolor=white:box=1:boxcolor=black@0.6,tile=6x$rows" \
      -frames:v 1 -q:v 4 "$OUT/$key.jpg"
  done
  cat "$OUT/status.txt"
  exit 0
fi

if [[ "$MODE" == "encode" ]]; then
  # plan.tsv columns: key  start_seconds  loop_seconds  output_raw_name  poster_name(or -)
  grep -v '^\s*#' "$PLAN" | while IFS=$'\t' read -r key start len rawname poster; do
    [[ -z "${key:-}" ]] && continue
    src="$(fetch "$key")"
    fade=1.2
    total="$(python3 -c "print($len+$fade)")"
    offset="$(python3 -c "print($len-$fade)")"
    out="$RAW_DIR/$rawname.mp4"
    echo "encoding $key [$start +${len}s] -> $out"
    # Seamless loop: body = [fade, len+fade), head = [0, fade). Crossfading the body's tail into the head
    # makes the last frame flow into frame 0 of the body (== source time start+fade).
    ffmpeg -v error -y -ss "$start" -t "$total" -i "$src" -filter_complex "\
      [0:v]fps=30,scale=1920:1080:force_original_aspect_ratio=increase:flags=lanczos,crop=1920:1080,setsar=1,format=yuv420p,split[a][b];\
      [a]trim=start=$fade:end=$total,setpts=PTS-STARTPTS[body];\
      [b]trim=start=0:end=$fade,setpts=PTS-STARTPTS[head];\
      [body][head]xfade=transition=fade:duration=$fade:offset=$offset,format=yuv420p[v]" \
      -map "[v]" -an -sn -dn -map_metadata -1 \
      -c:v libx264 -preset veryslow -profile:v high -level:v 4.1 -pix_fmt yuv420p \
      -crf 21 -maxrate 3000k -bufsize 6000k -g 60 -keyint_min 30 -bf 3 \
      -tune film -movflags +faststart "$out"
    if [[ "$poster" != "-" ]]; then
      ffmpeg -v error -y -i "$out" -frames:v 1 -vf "scale=1280:-2:flags=lanczos" -c:v libwebp -quality 82 \
        "$POSTER_DIR/$poster.webp"
    fi
    ls -l "$out"
  done
  exit 0
fi

echo "unknown mode: $MODE" >&2; exit 2
