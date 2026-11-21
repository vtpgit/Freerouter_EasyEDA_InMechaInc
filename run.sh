#!/bin/sh
# Starts the EasyEDA to Freerouting bridge. Keep the terminal open while you use the auto router.
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE" || exit 1
if [ ! -f "engine/freerouting-2.5.0.jar" ]; then
  echo "The routing engine is missing. Run ./setup.sh first."
  exit 1
fi
JAVA=java
if [ -x "runtime/bin/java" ]; then JAVA="runtime/bin/java"; fi
exec "$JAVA" -jar bridge.jar
