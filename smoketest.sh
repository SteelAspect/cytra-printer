#!/usr/bin/env bash
# 1.20.4 smoke test. Starts two dedicated 1.20.4 Fabric servers: one with the built jar (the placement helper,
# port 25590) and one with only Fabric API (25591). Then the dev client (src/smoketest) prints the test build in
# singleplayer (creative with and without the helper, survival, extras, settings, skipped outlines) and on both
# servers. Run it through ./headless.sh so no window shows.
# Logs: build/run/smokeserver/console.log, build/run/smokeplain/console.log, build/run/smoke/logs/latest.log.
set -uo pipefail
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-$HOME/.local/share/PrismLauncher/java/java-runtime-delta}"
MC=1.20.4 LOADER=0.19.5 INSTALLER=1.1.0 PLAYER=PrinterSmoke
export SMOKE_PORT=25590 SMOKE_PLAIN_PORT=25591
FABRIC_API=$(find ~/.gradle/caches/modules-2/files-2.1/net.fabricmc.fabric-api/fabric-api/0.97.3+1.20.4 -name 'fabric-api-0.97.3+1.20.4.jar' | head -1)

./gradlew remapJar --no-daemon -q || exit 1
JAR=$(ls build/libs/alloy-printer-*+1.20.4.jar | grep -v sources | head -1)

start_server() { # dir port jar...
    local d=$1 port=$2; shift 2
    rm -rf "$d/mods" "$d/world" "$d/logs"
    mkdir -p "$d/mods"
    [ -f build/run/fabric-server-launch.jar ] || curl -sSfL -o build/run/fabric-server-launch.jar \
        "https://meta.fabricmc.net/v2/versions/loader/$MC/$LOADER/$INSTALLER/server/jar" || exit 1
    cp build/run/fabric-server-launch.jar "$d/"
    cp "$@" "$d/mods/"
    echo "eula=true" > "$d/eula.txt"
    cat > "$d/server.properties" <<PROPS
online-mode=false
server-port=$port
level-type=minecraft\:flat
spawn-protection=0
view-distance=8
simulation-distance=6
spawn-monsters=false
spawn-animals=false
PROPS
    local uuid
    uuid=$(python3 -c "import hashlib,uuid;h=bytearray(hashlib.md5(b'OfflinePlayer:$PLAYER').digest());h[6]=h[6]&0x0f|0x30;h[8]=h[8]&0x3f|0x80;print(uuid.UUID(bytes=bytes(h)))")
    echo "[{\"uuid\":\"$uuid\",\"name\":\"$PLAYER\",\"level\":4,\"bypassesPlayerLimit\":false}]" > "$d/ops.json"
    rm -f "$d/stdin"; mkfifo "$d/stdin"
    (cd "$d" && exec "$JAVA_HOME/bin/java" -Xmx1G -jar fabric-server-launch.jar nogui < stdin > console.log 2>&1) &
}

wait_started() { # dir pid
    for _ in $(seq 1 180); do
        grep -q 'Done (' "$1/console.log" 2>/dev/null && return 0
        kill -0 "$2" 2>/dev/null || break
        sleep 1
    done
    echo "server in $1 did not start"; tail -40 "$1/console.log"; return 1
}

mkdir -p build/run
S=build/run/smokeserver P=build/run/smokeplain
start_server "$S" "$SMOKE_PORT" "$JAR" "$FABRIC_API"; SERVER=$!
exec 3> "$S/stdin"   # keep the fifo open so the server doesn't see EOF
start_server "$P" "$SMOKE_PLAIN_PORT" "$FABRIC_API"; PLAIN=$!
exec 4> "$P/stdin"
wait_started "$S" $SERVER && wait_started "$P" $PLAIN || { kill $SERVER $PLAIN 2>/dev/null; exit 1; }
echo "servers started"

./gradlew runSmokeTest --no-daemon -q
echo stop >&3; exec 3>&-
echo stop >&4; exec 4>&-
wait $SERVER $PLAIN

echo "--- server with the jar"
grep -E 'cytraprinter|Alloy Printer|ERROR|Exception|Mixin|mixin' "$S/console.log" | head -30
echo "--- plain server"
grep -E 'ERROR|Exception|kicked|lost connection' "$P/console.log" | head -20
echo "--- client"
grep -E 'PrinterSmoke\]|PASS |FAIL |wrong' build/run/smoke/logs/latest.log | head -200
grep -q '\[PrinterSmoke\] PASS' build/run/smoke/logs/latest.log
