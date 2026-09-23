#!/system/bin/sh
# LibrePods — HyperOS AAP channel conflict fix.
#
# HyperOS's own AirPods adapter (com.xiaomi.bluetooth) opens its own AAP L2CAP
# session on PSM 0x1001 — the same channel LibrePods uses. Its gate reads
#   /data/user_de/0/com.xiaomi.bluetooth/files/fc_resources/<ver>/fc_support_airpods.json
# and opens the session when the "ConnectL2cap" key is absent (factory default)
# or when it contains the local AirPods "type". Adding the key with a value that
# does NOT contain the local type makes the gate block, so LibrePods owns the
# session exclusively.
#
# This patch uses empty arrays ([]), which exclude every AirPods type, so it
# works regardless of the user's model. If some HyperOS build mishandles empty
# arrays, replace [] with ["0000"] below (a type no real AirPods uses).
#
# Root-only, HyperOS-only, idempotent. Runs at every boot so it also re-applies
# after an OTA resets the file to factory. See docs/hyperos-aap-channel.md.

# Gate: only MIUI / HyperOS has com.xiaomi.bluetooth's adapter.
[ -n "$(getprop ro.miui.ui.version.name)" ] || exit 0

JSON_DIR=/data/user_de/0/com.xiaomi.bluetooth/files/fc_resources
JSON=

# The adapter creates this file on first run, which may happen after this
# script runs. Wait up to 60s for it to appear.
i=0
while [ $i -lt 30 ]; do
    JSON=$(find "$JSON_DIR" -maxdepth 2 -name fc_support_airpods.json 2>/dev/null | head -n1)
    [ -n "$JSON" ] && break
    i=$((i + 1))
    sleep 2
done

[ -n "$JSON" ] || exit 0

# Idempotency: the factory file has no "ConnectL2cap" key, so its presence means
# we already patched (and it survives OTA resets, which put it back to factory).
grep -q '"ConnectL2cap"' "$JSON" 2>/dev/null && exit 0

# Back up the pristine file once.
if [ ! -f "$JSON.bak_librepods" ]; then
    cp -a "$JSON" "$JSON.bak_librepods"
fi

# f2fs quirk: never truncate the existing inode in place — write a NEW file and
# rename over it, or the write fails with EOPNOTSUPP.
TMP="$JSON.new"
chattr -i "$JSON" 2>/dev/null

# Rebuild the JSON: keep every existing key, drop the trailing root "}", append
# the three keys (empty arrays) and close the object again. Comma-joins onto the
# last existing entry.
awk '
    { line[NR] = $0 }
    END {
        last = NR
        while (last > 0 && line[last] ~ /^[[:space:]]*$/) last--
        for (i = 1; i < last; i++) {
            if (i == last - 1) {
                sub(/[[:space:]]*$/, "", line[i])
                print line[i] ","
            } else {
                print line[i]
            }
        }
        print "  \"ConnectL2cap\": [],"
        print "  \"EarDetection\": [],"
        print "  \"NoiseControl\": []"
        print "}"
    }
' "$JSON" > "$TMP"

chown bluetooth:bluetooth "$TMP"
chmod 600 "$TMP"
chcon u:object_r:bluetooth_data_file:s0 "$TMP" 2>/dev/null

mv "$TMP" "$JSON"
chattr +i "$JSON" 2>/dev/null

# Make the adapter re-read the patched config.
am force-stop com.xiaomi.bluetooth 2>/dev/null
