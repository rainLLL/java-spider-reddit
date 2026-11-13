#!/bin/zsh
set -euo pipefail

SCRIPT_DIR="${0:A:h}"
cd "$SCRIPT_DIR"

if ! command -v java >/dev/null 2>&1; then
  echo "未找到 Java。请先安装 JDK 17 或更高版本。" >&2
  exit 1
fi

JAVA_MAJOR=$(java -version 2>&1 | awk -F'[\".]' '/version/ {print $2; exit}')
if [[ -z "$JAVA_MAJOR" || "$JAVA_MAJOR" -lt 17 ]]; then
  echo "需要 JDK 17 或更高版本，当前版本：$JAVA_MAJOR" >&2
  exit 1
fi

if [[ ! -f target/java-spider-reddit-1.0.0.jar ]]; then
  ./mvnw -q -DskipTests package
fi

exec java -jar target/java-spider-reddit-1.0.0.jar "$@"
