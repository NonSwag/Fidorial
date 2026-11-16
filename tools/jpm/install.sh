#!/bin/sh
# Installs the jpm Fidorial builds with: jpm 0.7.1 from source, with the patches in this directory applied,
# as ~/.jpm/bin/jpm (a launcher for the jar build; needs a JDK 25 as JAVA_HOME or on PATH).
#
# Why not the released binary: jpm 0.7.1's native binary is built with JDK 21, so it cannot read the
# module-info.class of modules compiled for Java 25 and puts every one of them on the class path, which
# breaks `requires fr.fidorial`. See docs/jpm-handoff.md; drop this script once a release has the fix.
#
#   tools/jpm/install.sh            # JPM_HOME changes the install root (default ~/.jpm)
set -eu

version=0.7.1
here="$(cd "$(dirname "$0")" && pwd)"
home="${JPM_HOME:-$HOME/.jpm}"
src="$home/src/jpm-$version-fidorial"
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}"

if ! "${java_bin}javac" --version 2>/dev/null | grep -q ' 2[5-9]\| [3-9][0-9]'; then
  echo "tools/jpm/install.sh: needs JDK 25 or newer (JAVA_HOME or javac on PATH)" >&2
  exit 1
fi

rm -rf "$src"
mkdir -p "$(dirname "$src")" "$home/bin"
git clone --quiet --depth 1 --branch "$version" https://github.com/java-package-manager/jpm.git "$src"
for patch in "$here"/*.patch; do
  git -C "$src" apply "$patch"
done

build="$src/.cache/bootstrap"
mkdir -p "$build/classes" "$build/gen/dev/jpm"
printf 'package dev.jpm;\npublic final class BuildConfig {\n    private BuildConfig() {}\n    public static final String NAME = "jpm";\n    public static final String VERSION = "%s";\n}\n' \
  "$version" > "$build/gen/dev/jpm/BuildConfig.java"
find "$src/src" "$build/gen" -name '*.java' > "$build/sources.txt"
"${java_bin}javac" --release 21 -nowarn -encoding UTF-8 -d "$build/classes" @"$build/sources.txt" 2>/dev/null
printf 'Main-Class: dev.jpm.cli.Main\n' > "$build/manifest.txt"
"${java_bin}jar" --create --file "$home/bin/jpm-$version-fidorial.jar" --manifest "$build/manifest.txt" -C "$build/classes" .

java_home="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}"
cat > "$home/bin/jpm" <<EOF
#!/bin/sh
exec "$java_home/bin/java" -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xshare:auto -jar "$home/bin/jpm-$version-fidorial.jar" "\$@"
EOF
chmod +x "$home/bin/jpm"
echo "Installed $("$home/bin/jpm" --version) (with Fidorial's patches) to $home/bin/jpm"
