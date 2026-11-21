#!/bin/sh
# Build bridge.jar from src/. Needs a JDK 17 or later.
set -e
unset JAVA_TOOL_OPTIONS
HERE="$(cd "$(dirname "$0")" && pwd)"
rm -rf "$HERE/classes" && mkdir -p "$HERE/classes/META-INF"
javac -nowarn -encoding UTF-8 --release 17 -d "$HERE/classes" "$HERE"/src/bridge/*.java
cp "$HERE/LICENSE" "$HERE/NOTICE.md" "$HERE/classes/META-INF/" 2>/dev/null || true
printf 'Main-Class: bridge.Main\nImplementation-Title: EasyEDA to Freerouting bridge\n' > "$HERE/manifest.txt"
(cd "$HERE/classes" && jar cfm ../bridge.jar ../manifest.txt .)
rm -f "$HERE/manifest.txt"
ls -la "$HERE/bridge.jar"
