#!/bin/sh
if [ -n "$SHARED_INDEX_DIR" ] && [ ! -f "$SHARED_INDEX_DIR/meta.bin" ]; then
  mkdir -p "$SHARED_INDEX_DIR"
  flock "$SHARED_INDEX_DIR/.lock" cp /app/index/* "$SHARED_INDEX_DIR/" 2>/dev/null || true
  export INDEX_DIR="$SHARED_INDEX_DIR"
elif [ -n "$SHARED_INDEX_DIR" ]; then
  export INDEX_DIR="$SHARED_INDEX_DIR"
fi

exec java \
  --add-opens java.base/sun.misc=ALL-UNNAMED \
  --add-opens java.base/java.nio=ALL-UNNAMED \
  -XX:+UseSerialGC \
  -Xmx48m \
  -XX:MaxMetaspaceSize=32m \
  -Xss256k \
  -jar app.jar
