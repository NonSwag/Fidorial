#!/bin/sh
# Installs the jpm Fidorial builds with as ~/.jpm/bin/jpm: a launcher for the jar build of jpm, built from source
# (needs a JDK 25 as JAVA_HOME or on PATH).
#
# Why not a released binary: Fidorial needs jpm fixes that are not released yet (pull requests on
# java-package-manager/jpm, merged together on its integration/fidorial branch): Java 25 module descriptors in
# the native binary, patches with dependencies, isolated modules, workspace inheritance, `-J` javac flags,
# `info --classpath`, JPM_EXE and //DEPS for scripts, and not caching failed downloads. Pinned to one commit so
# every build uses the same jpm. Drop this script once a jpm release has them.
#
#   tools/jpm/install.sh            # JPM_HOME changes the install root (default ~/.jpm)
set -eu

commit=eb9d637b767898817d2af46809e28d30c9448f74
home="${JPM_HOME:-$HOME/.jpm}"
src="$home/src/jpm-${commit%"${commit#???????}"}"
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}"

if ! "${java_bin}javac" --version 2>/dev/null | grep -q ' 2[5-9]\| [3-9][0-9]'; then
  echo "tools/jpm/install.sh: needs JDK 25 or newer (JAVA_HOME or javac on PATH)" >&2
  exit 1
fi

rm -rf "$src"
mkdir -p "$src" "$home/bin"
git -C "$src" init --quiet
git -C "$src" fetch --quiet --depth 1 https://github.com/java-package-manager/jpm.git "$commit"
git -C "$src" checkout --quiet FETCH_HEAD
version=$(sed -n 's/^ *"version": *"\([^"]*\)".*/\1/p' "$src/project.jsonc" | head -n 1)-${commit%"${commit#???????}"}

build="$src/.cache/bootstrap"
mkdir -p "$build/classes" "$build/gen/dev/jpm"
printf 'package dev.jpm;\npublic final class BuildConfig {\n    private BuildConfig() {}\n    public static final String NAME = "jpm";\n    public static final String VERSION = "%s";\n}\n' \
  "$version" > "$build/gen/dev/jpm/BuildConfig.java"
find "$src/src" "$build/gen" -name '*.java' > "$build/sources.txt"
"${java_bin}javac" --release 21 -nowarn -encoding UTF-8 -d "$build/classes" @"$build/sources.txt" 2>/dev/null
printf 'Main-Class: dev.jpm.cli.Main\n' > "$build/manifest.txt"
"${java_bin}jar" --create --file "$home/bin/jpm-$version.jar" --manifest "$build/manifest.txt" -C "$build/classes" .

java_home="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}"
cat > "$home/bin/jpm" <<LAUNCHER
#!/bin/sh
# scripts and hooks call back into this jpm through JPM_EXE
JPM_EXE="$home/bin/jpm"; export JPM_EXE
exec "$java_home/bin/java" -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xshare:auto -jar "$home/bin/jpm-$version.jar" "\$@"
LAUNCHER
chmod +x "$home/bin/jpm"
echo "Installed $("$home/bin/jpm" --version) to $home/bin/jpm"
