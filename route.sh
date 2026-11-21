#!/bin/sh
# Routes an EasyEDA Standard PCB file: ./route.sh board.json [result.json] [--time seconds] [--rules rules.json]
# The result is board.routed.json next to the input file. The input file does not change.
HERE="$(cd "$(dirname "$0")" && pwd)"
if [ $# -lt 1 ]; then
  echo "Use: ./route.sh board.json [result.json] [--time seconds] [--rules rules.json]"
  exit 64
fi
if [ ! -f "$HERE/engine/freerouting-2.5.0.jar" ]; then
  echo "The routing engine is missing. Run ./setup.sh first."
  exit 1
fi
JAVA=java
if [ -x "$HERE/runtime/bin/java" ]; then JAVA="$HERE/runtime/bin/java"; fi
exec "$JAVA" -Dbridge.home="$HERE" -jar "$HERE/bridge.jar" route "$@"
