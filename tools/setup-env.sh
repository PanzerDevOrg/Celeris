#!/usr/bin/env bash
# Prepares a fresh cloud container to build Celeris: JDK 25 for Gradle and the
# ../panzer-build-logic link. Source it so JAVA_HOME stays set:
#   source tools/setup-env.sh
# Expects PanzerBuildLogic cloned next to Celeris (or already as panzer-build-logic).
set -euo pipefail
repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
parent=$(dirname "$repo")

if [ ! -e "$parent/panzer-build-logic" ]; then
  if [ -d "$parent/PanzerBuildLogic" ]; then
    ln -s PanzerBuildLogic "$parent/panzer-build-logic"
  else
    git clone https://github.com/PanzerDevOrg/PanzerBuildLogic.git "$parent/panzer-build-logic"
  fi
fi

jdk_root=${CELERIS_JDK_DIR:-/tmp/jdk25}
if ! ls -d "$jdk_root"/jdk-25* >/dev/null 2>&1; then
  mkdir -p "$jdk_root"
  curl -fsSL -o "$jdk_root/jdk.tgz" \
    "https://api.adoptium.net/v3/binary/latest/25/ga/linux/x64/jdk/hotspot/normal/eclipse"
  tar xzf "$jdk_root/jdk.tgz" -C "$jdk_root"
  rm "$jdk_root/jdk.tgz"
fi
export JAVA_HOME=$(ls -d "$jdk_root"/jdk-25* | head -1)
export PATH="$JAVA_HOME/bin:$PATH"
echo "JAVA_HOME=$JAVA_HOME"
