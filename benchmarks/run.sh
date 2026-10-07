#!/usr/bin/env bash
# Runs the benchmarks behind docs/benchmarks.md and redraws its charts.
#
#   CELERIS=<Celeris classes: build/classes/java/main:build/classes/java/ffm, or a celeris jar>
#   NATIVES=<folder holding natives/<platform>/ with libzstd and libceleris_physics>
#   LIBS=<folder with jmh-core 1.37, jmh-generator-annprocess 1.37, jopt-simple 5.0.4,
#         commons-math3 3.6.1, jctools-core 4.0.5, lz4-java 1.8.0, zstd-jni 1.5.7-6, slf4j-api, fastutil>
#   bash benchmarks/run.sh            # JDK 21 on PATH; results land in benchmarks/results/
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
out=$(mktemp -d)
cp="$out:$CELERIS:$NATIVES:$(printf '%s:' "$LIBS"/*.jar)"
res="$here/results"
mkdir -p "$res"

javac --release 21 --enable-preview -nowarn -d "$out" -cp "$cp" \
  -processorpath "$LIBS/jmh-generator-annprocess-1.37.jar:$LIBS/jmh-core-1.37.jar" "$here"/src/bench/*.java

jmh() { java -cp "$cp" org.openjdk.jmh.Main "$@"; }
jmh CompressionBench -jvmArgsAppend "-Dbench.binding=jni" -rf json -rff "$res/compress-jni.json"
jmh "CompressionBench.celeris" -jvmArgsAppend "--enable-preview -Dbench.binding=ffm" -rf json -rff "$res/compress-ffm.json"
jmh PhysicsBench -jvmArgsAppend "--enable-preview -Dceleris.physics.threads=0" -rf json -rff "$res/physics-1t.json"
jmh PhysicsBench -p items=32768 -jvmArgsAppend "--enable-preview -Dceleris.physics.threads=3" -rf json -rff "$res/physics-mt.json"
java -cp "$cp" bench.QueueBench > "$res/queues-after.csv"

python3 "$here/charts.py" "$res" "$here/../docs/media/benchmarks"
