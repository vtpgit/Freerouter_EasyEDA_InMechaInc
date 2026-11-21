#!/bin/sh
# Setup for the EasyEDA to Freerouting bridge on Linux and macOS.
# 1. Downloads the routing engine (Freerouting 2.5.0, GPLv3) from its GitHub release and checks the file.
# 2. If no Java 25 or later is installed, downloads the Eclipse Temurin Java runtime into the folder "runtime".
# Nothing is installed outside this folder.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
ENGINE_URL="https://github.com/freerouting/freerouting/releases/download/v2.5.0/freerouting-2.5.0.jar"
ENGINE_SHA256="f6f51bb02245e8e717f9359bd260cc9c5c0b1bc0acc8b7cb2cd5b8ffeb5de3c7"
REQUIRED_JAVA=25

java_major() {
  "$1" -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -n 1
}
sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

mkdir -p "$HERE/engine"
JAR="$HERE/engine/freerouting-2.5.0.jar"
if [ ! -f "$JAR" ]; then
  echo "Downloading the routing engine (65 MB) ..."
  curl -fL --progress-bar -o "$JAR" "$ENGINE_URL"
fi
if [ "$(sha256_of "$JAR")" != "$ENGINE_SHA256" ]; then
  rm -f "$JAR"
  echo "The downloaded engine file is not the expected one. It was deleted. Run setup again." >&2
  exit 1
fi
echo "Routing engine: ok."

if [ -x "$HERE/runtime/bin/java" ]; then
  echo "Java runtime in the folder \"runtime\": ok."
else
  FOUND=""
  if command -v java >/dev/null 2>&1; then FOUND="$(java_major java)"; fi
  if [ -n "$FOUND" ] && [ "$FOUND" -ge "$REQUIRED_JAVA" ]; then
    echo "Installed Java $FOUND is new enough. No download needed."
  else
    case "$(uname -s)" in
      Darwin) OS=mac ;;
      *) OS=linux ;;
    esac
    case "$(uname -m)" in
      arm64|aarch64) ARCH=aarch64 ;;
      *) ARCH=x64 ;;
    esac
    URL="https://api.adoptium.net/v3/binary/latest/$REQUIRED_JAVA/ga/$OS/$ARCH/jre/hotspot/normal/eclipse"
    echo "Downloading the Java $REQUIRED_JAVA runtime (Eclipse Temurin, about 50 MB) ..."
    rm -rf "$HERE/runtime-unpack" && mkdir -p "$HERE/runtime-unpack"
    curl -fL --progress-bar -o "$HERE/runtime-download.tar.gz" "$URL"
    tar -xzf "$HERE/runtime-download.tar.gz" -C "$HERE/runtime-unpack"
    JAVA_BIN="$(find "$HERE/runtime-unpack" -type f -name java -path '*/bin/java' | head -n 1)"
    if [ -z "$JAVA_BIN" ]; then echo "The Java download has an unexpected layout." >&2; exit 1; fi
    rm -rf "$HERE/runtime"
    mv "$(dirname "$(dirname "$JAVA_BIN")")" "$HERE/runtime"
    rm -rf "$HERE/runtime-unpack" "$HERE/runtime-download.tar.gz"
    echo "Java runtime: ok."
  fi
fi
echo
echo "Setup is complete. Start the router with ./run.sh"
