#!/usr/bin/env bash
# Builds one Fold .mappack per US state from Geofabrik extracts.
#   ./build-states.sh                 -> all 50 states + DC (about 2 hours, ~30 GB)
#   ./build-states.sh montana wyoming -> just those
# Needs: java 17+, curl, and fold-packer.jar next to this script. Re-runs skip states already built.
set -u
cd "$(dirname "$0")"
ALL="alabama alaska arizona arkansas california colorado connecticut delaware district-of-columbia florida georgia hawaii idaho illinois indiana iowa kansas kentucky louisiana maine maryland massachusetts michigan minnesota mississippi missouri montana nebraska nevada new-hampshire new-jersey new-mexico new-york north-carolina north-dakota ohio oklahoma oregon pennsylvania rhode-island south-carolina south-dakota tennessee texas utah vermont virginia washington west-virginia wisconsin wyoming"
STATES="${*:-$ALL}"
HEAP="${FOLD_HEAP:-8g}"
EXTRA="${FOLD_PACKER_ARGS:-}"        # e.g. FOLD_PACKER_ARGS="--max-zoom 14" for packs about 40% smaller
# Find a Java 17+ runtime. Homebrew's openjdk is "keg-only" (not on PATH), so look there too.
JAVA="${JAVA:-}"
if [ -z "$JAVA" ]; then
  for c in java /opt/homebrew/opt/openjdk/bin/java /opt/homebrew/opt/openjdk@21/bin/java /opt/homebrew/opt/openjdk@17/bin/java /usr/local/opt/openjdk/bin/java /usr/local/opt/openjdk@21/bin/java; do
    if command -v "$c" >/dev/null 2>&1 && "$c" -version >/dev/null 2>&1; then JAVA="$c"; break; fi
  done
fi
if [ -z "$JAVA" ] && command -v /usr/libexec/java_home >/dev/null 2>&1; then
  h="$(/usr/libexec/java_home -v 17+ 2>/dev/null)" && [ -n "$h" ] && JAVA="$h/bin/java"
fi
if [ -z "$JAVA" ] || ! "$JAVA" -version >/dev/null 2>&1; then
  echo "No Java runtime found. Install one (mac: brew install openjdk@21; linux: sudo apt install openjdk-21-jre) or run: JAVA=/path/to/java ./build-states.sh"; exit 1
fi
ver="$("$JAVA" -version 2>&1 | grep -m1 'version "' | sed -E 's/.*version "([0-9]+).*/\1/')"
if [ "${ver:-0}" -lt 17 ] 2>/dev/null; then echo "Java 17 or newer is required (found $ver)"; exit 1; fi
echo "using $JAVA (java $ver)"
[ -s fold-packer.jar ] || { echo "fold-packer.jar must be in this folder"; exit 1; }
mkdir -p pbf packs
for s in $STATES; do
  name="$(echo "$s" | tr '-' ' ' | awk '{for(i=1;i<=NF;i++) $i=toupper(substr($i,1,1)) substr($i,2)}1')"
  out="packs/$s.mappack"
  if [ -s "$out" ]; then echo "== $name: already built, skipping"; continue; fi
  pbf="pbf/$s-latest.osm.pbf"
  if [ ! -s "$pbf" ]; then
    echo "== $name: downloading"
    curl -fL --retry 5 -o "$pbf.part" "https://download.geofabrik.de/north-america/us/$s-latest.osm.pbf" && mv "$pbf.part" "$pbf" || { echo "!! download failed for $s"; continue; }
  fi
  echo "== $name: building"
  extra="$EXTRA"
  # Alaska is a third of the country's area with few roads: cap its tiles unless told otherwise
  if [ "$s" = "alaska" ] && [ -z "$extra" ]; then extra="--max-zoom 13"; fi
  "$JAVA" -Xmx"$HEAP" -jar fold-packer.jar --pbf "$pbf" --out "$out.part" --name "$name" $extra && mv "$out.part" "$out" || { echo "!! build failed for $s"; rm -f "$out.part"; }
done
echo "done. packs are in ./packs — copy the .mappack files to the phone's Downloads folder, then MAPS -> IMPORT -> IMPORT ALL."
