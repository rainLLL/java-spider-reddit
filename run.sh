#!/bin/zsh
set -euo pipefail

SCRIPT_DIR="${0:A:h}"
cd "$SCRIPT_DIR"

JAR_FILE="$SCRIPT_DIR/target/java-spider-reddit-1.0.0.jar"
BUNDLED_JAVA="$SCRIPT_DIR/runtime/Contents/Home/bin/java"

if [[ -x "$BUNDLED_JAVA" ]]; then
  BUNDLE_ARCH=$(<"$SCRIPT_DIR/RUNTIME_ARCHITECTURE")
  if [[ "$BUNDLE_ARCH" != "$(uname -m)" ]]; then
    echo "运行包架构为 $BUNDLE_ARCH，当前 Mac 架构为 $(uname -m)。请使用对应版本的运行包。" >&2
    exit 1
  fi
  JAVA_CMD="$BUNDLED_JAVA"
else
  if ! command -v java >/dev/null 2>&1; then
    echo "未找到 Java。请使用内置运行环境的交付包，或安装 JDK 17 或更高版本。" >&2
    exit 1
  fi
  JAVA_CMD=$(command -v java)
fi

JAVA_MAJOR=$("$JAVA_CMD" -version 2>&1 | awk -F'[\".]' '/version/ {print $2; exit}')
if [[ -z "$JAVA_MAJOR" || "$JAVA_MAJOR" -lt 17 ]]; then
  echo "需要 JDK 17 或更高版本，当前版本：$JAVA_MAJOR" >&2
  exit 1
fi

if [[ ! -f "$JAR_FILE" && -x "$BUNDLED_JAVA" ]]; then
  echo "运行包中缺少 JAR：$JAR_FILE" >&2
  exit 1
fi

if [[ ! -f "$JAR_FILE" ]]; then
  ./mvnw -q -DskipTests package
fi

exec "$JAVA_CMD" -jar "$JAR_FILE" "$@"
