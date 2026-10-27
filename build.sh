#!/usr/bin/env bash
# Build and run the Java port of resynthesizer. No third-party dependencies.
#   ./build.sh              compile the engine
#   ./build.sh test         compile + run tests
#   ./build.sh demo         compile + heal the images in examples/
#   ./build.sh jar          build dist/resynth.jar (executable, Java 8) + sources jar
#
# Requires JDK 17+ (the jar itself targets Java 8). Set JAVA_HOME, or edit JDK below.
set -euo pipefail
cd "$(dirname "$0")"

JDK="${JAVA_HOME:-/c/Users/xulih/.jdks/jdk-17.0.20.1+1}"
JAVAC="$JDK/bin/javac"
JAVA="$JDK/bin/java"

cmd="${1:-build}"

build() {
  mkdir -p out
  "$JAVAC" -d out src/main/java/resynth/*.java
}

# Executable jar for external callers (ImageTrans / B4J). Java 8 bytecode so it runs on
# any JRE the host app ships; no third-party dependencies.
build_jar() {
  # Only clean our own intermediates: dist/ is the user's drop folder for test images.
  rm -rf build/lib build/src
  mkdir -p build/lib dist
  "$JAVAC" --release 8 -Xlint:-options -d build/lib src/main/java/resynth/*.java
  cp COPYING build/lib/COPYING
  cat > build/MANIFEST.MF <<'EOF'
Manifest-Version: 1.0
Main-Class: resynth.HealCli
Implementation-Title: resynth
Implementation-Version: 0.1
EOF
  "$JDK/bin/jar" --create --file dist/resynth.jar --manifest build/MANIFEST.MF -C build/lib .

  mkdir -p build/src/src/main/java/resynth
  cp src/main/java/resynth/*.java build/src/src/main/java/resynth/
  cp COPYING build/src/COPYING
  "$JDK/bin/jar" --create --file dist/resynth-sources.jar -C build/src .

  echo "built dist/resynth.jar and dist/resynth-sources.jar"
}

case "$cmd" in
  ""|build)
    build
    echo "compiled to out/"
    echo "  $JAVA -cp out resynth.Main     <in.png> <out.png> <x> <y> <w> <h> [patch] [probes]"
    echo "  $JAVA -cp out resynth.MainMask <image> <mask> <out.png>   [patch] [probes] [neighborWeight]"
    ;;
  test)
    build
    # The local Maven repo has jupiter-api but not the platform launcher,
    # so tests are run by a small reflection-based runner.
    JUNIT_CP=$(
      find "$HOME/.m2/repository/org/junit" \
           "$HOME/.m2/repository/org/opentest4j" \
           "$HOME/.m2/repository/org/apiguardian" -name '*.jar' 2>/dev/null \
      | sed 's|^/c|C:|' | tr '\n' ';'
    )
    if [ -z "$JUNIT_CP" ]; then
      echo "JUnit not found under ~/.m2; only the engine was built." >&2
      exit 1
    fi
    "$JAVAC" -d out -cp "out;$JUNIT_CP" src/test/java/resynth/*.java
    "$JAVA" -cp "out;$JUNIT_CP" resynth.TestRunner
    ;;
  demo)
    build
    mkdir -p out/demo
    "$JAVA" -Xmx2g -cp out resynth.MainMask examples/image.jpg examples/thresh.png out/demo/image-heal.png 30 500
    "$JAVA" -Xmx2g -cp out resynth.MainMask examples/cropped.png examples/cropped-mask.png out/demo/cropped-heal.png 30 500
    echo "results in out/demo/"
    ;;
  jar|lib)
    build_jar
    ;;
  *)
    echo "usage: $0 [build|test|demo|jar]" >&2
    exit 2
    ;;
esac
