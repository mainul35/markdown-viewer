#!/bin/sh
#
# Starts MDViewer with the settings its own runtime needs, so nobody has to know
# them. Runs from wherever it is unzipped.
#
# Three things it handles:
#
#   Wayland. JavaFX has no Wayland backend at all - it draws through GTK on X11
#   and reaches a Wayland session only via XWayland. Without GDK_BACKEND=x11 the
#   window may never appear, and the failure names graphics pipelines rather than
#   the cause.
#
#   Two warnings from Java 24 onwards, both about JavaFX rather than about this
#   application: its rasteriser uses sun.misc.Unsafe, and loading its native
#   libraries is a restricted operation. Nothing here can stop JavaFX doing
#   either; the flags say "yes, that is expected" so the console stays readable.
#   They are only passed on a JVM old enough to reject them - an unknown option
#   stops the JVM starting, which would be a far worse trade than a warning.
#
#   Being started from somewhere else, by double-click or a launcher, where the
#   working directory is not this folder.

set -eu

here=$(cd "$(dirname "$0")" && pwd)

# Whatever jar is beside this script, rather than a version written into it. A
# release that renames the jar and forgets the launcher ships something that
# cannot start, and the name is the one thing that changes every release.
jar=$(ls "$here"/mdviewer-*.jar 2>/dev/null | head -1)

if [ -z "$jar" ]; then
    echo "No mdviewer-*.jar next to this script (looked in $here)." >&2
    exit 1
fi

if ! command -v java >/dev/null 2>&1; then
    echo "Java is not installed, or not on the PATH. MDViewer needs Java 21 or newer." >&2
    exit 1
fi

# A Wayland session with no backend chosen: pick X11, because that is the only
# one JavaFX has. An explicit GDK_BACKEND is left alone - somebody who set it
# meant it.
if [ -n "${WAYLAND_DISPLAY:-}" ] && [ -z "${GDK_BACKEND:-}" ]; then
    GDK_BACKEND=x11
    export GDK_BACKEND
fi

# "openjdk version "25.0.4" ..." or "java version "1.8.0_401"" -> 25, 1
major=$(java -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/')

flags=""
if [ "${major:-0}" -ge 24 ] 2>/dev/null; then
    flags="--enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow"
fi

# shellcheck disable=SC2086
exec java $flags -jar "$jar" "$@"
