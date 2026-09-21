#!/usr/bin/env bash
# Подготовка локальной офлайн-подложки (Москва + Московская область).
# Скачивает CLI pmtiles, извлекает регион из Protomaps build и локальные глифы.
# Полный экстракт (moscow.pmtiles) НЕ коммитится; placeholder и глифы — да.
#
# Использование:
#   bash scripts/fetch-basemap.sh            # placeholder (z6) + moscow (z14) + глифы
#   bash scripts/fetch-basemap.sh --placeholder-only
#   PROTOMAPS_DATE=20260919 bash scripts/fetch-basemap.sh
#   MAXZOOM=13 BBOX="35.0,54.0,40.5,57.0" bash scripts/fetch-basemap.sh
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="$ROOT_DIR/public/basemap"
CACHE_DIR="${BASEMAP_CACHE_DIR:-$ROOT_DIR/.cache/basemap}"
BBOX="${BBOX:-35.0,54.0,40.5,57.0}"
MAXZOOM="${MAXZOOM:-14}"
PLACEHOLDER_MAXZOOM="${PLACEHOLDER_MAXZOOM:-6}"
FONTS=("Noto Sans Regular" "Noto Sans Medium" "Noto Sans Italic")
FONT_RANGES=("0-255" "256-511" "512-767" "768-1023" "1024-1279" "8192-8447" "8448-8703")

PLACEHOLDER_ONLY=0
for arg in "$@"; do
  case "$arg" in
    --placeholder-only) PLACEHOLDER_ONLY=1 ;;
    *) echo "Неизвестный аргумент: $arg" >&2; exit 2 ;;
  esac
done

mkdir -p "$OUT_DIR/fonts" "$CACHE_DIR"

# --- 1. CLI pmtiles ---------------------------------------------------------
PMTILES_BIN="$CACHE_DIR/pmtiles"
if [ ! -x "$PMTILES_BIN" ]; then
  echo "[basemap] Скачиваю pmtiles CLI…"
  VERSION="$(curl -fsSL https://api.github.com/repos/protomaps/go-pmtiles/releases/latest \
    | python3 -c 'import sys,json; print(json.load(sys.stdin)["tag_name"])')"
  ARCH="$(uname -m)"
  case "$ARCH" in
    x86_64|amd64) ASSET="go-pmtiles_${VERSION#v}_Linux_x86_64.tar.gz" ;;
    aarch64|arm64) ASSET="go-pmtiles_${VERSION#v}_Linux_arm64.tar.gz" ;;
    *) echo "[basemap] Неподдерживаемая архитектура: $ARCH" >&2; exit 1 ;;
  esac
  curl -fsSL "https://github.com/protomaps/go-pmtiles/releases/download/${VERSION}/${ASSET}" \
    -o "$CACHE_DIR/pmtiles.tar.gz"
  tar -xzf "$CACHE_DIR/pmtiles.tar.gz" -C "$CACHE_DIR" pmtiles
  chmod +x "$PMTILES_BIN"
fi

# --- 2/3. Экстракты (источник нужен, только если чего-то нет) --------------
need_placeholder=0
if [ ! -s "$OUT_DIR/placeholder.pmtiles" ] || [ "${FORCE:-0}" = "1" ]; then
  need_placeholder=1
fi
need_moscow=0
if [ "$PLACEHOLDER_ONLY" -eq 0 ] \
  && { [ ! -s "$OUT_DIR/moscow.pmtiles" ] || [ "${FORCE:-0}" = "1" ]; }; then
  need_moscow=1
fi

if [ "$need_placeholder" -eq 1 ] || [ "$need_moscow" -eq 1 ]; then
  if [ -z "${PROTOMAPS_DATE:-}" ]; then
    for offset in 1 2 3 4 5 6 7; do
      candidate="$(date -d "-${offset} day" +%Y%m%d 2>/dev/null || date -v-"${offset}"d +%Y%m%d)"
      if curl -fsI "https://build.protomaps.com/${candidate}.pmtiles" >/dev/null 2>&1; then
        PROTOMAPS_DATE="$candidate"
        break
      fi
    done
  fi
  if [ -z "${PROTOMAPS_DATE:-}" ]; then
    echo "[basemap] Не найден доступный build Protomaps; задайте PROTOMAPS_DATE." >&2
    exit 1
  fi
  SOURCE="https://build.protomaps.com/${PROTOMAPS_DATE}.pmtiles"
  echo "[basemap] Источник: $SOURCE"

  if [ "$need_placeholder" -eq 1 ]; then
    echo "[basemap] placeholder.pmtiles (z$PLACEHOLDER_MAXZOOM)…"
    "$PMTILES_BIN" extract "$SOURCE" "$OUT_DIR/placeholder.pmtiles" \
      "--bbox=$BBOX" "--maxzoom=$PLACEHOLDER_MAXZOOM"
  fi
  if [ "$need_moscow" -eq 1 ]; then
    echo "[basemap] moscow.pmtiles (z$MAXZOOM)…"
    "$PMTILES_BIN" extract "$SOURCE" "$OUT_DIR/moscow.pmtiles" \
      "--bbox=$BBOX" "--maxzoom=$MAXZOOM"
  fi
else
  echo "[basemap] Экстракты уже есть — пропускаю (FORCE=1 для пересборки)."
fi

# --- 4. Локальные глифы -----------------------------------------------------
for font in "${FONTS[@]}"; do
  encoded="${font// /%20}"
  mkdir -p "$OUT_DIR/fonts/$font"
  for range in "${FONT_RANGES[@]}"; do
    target="$OUT_DIR/fonts/$font/$range.pbf"
    if [ -s "$target" ]; then
      continue
    fi
    curl -fsSL "https://protomaps.github.io/basemaps-assets/fonts/${encoded}/${range}.pbf" \
      -o "$target"
  done
done

# --- 5. Локальный спрайт (иконки POI) ---------------------------------------
mkdir -p "$OUT_DIR/sprites"
for file in light.json light.png light@2x.json light@2x.png; do
  target="$OUT_DIR/sprites/$file"
  if [ -s "$target" ]; then
    continue
  fi
  curl -fsSL "https://protomaps.github.io/basemaps-assets/sprites/v4/$file" -o "$target"
done

echo "[basemap] Готово. Файлы в $OUT_DIR (moscow.pmtiles не коммитится)."
echo "[basemap] Стиль: node scripts/build-style.mjs (или pnpm run basemap:style)."
