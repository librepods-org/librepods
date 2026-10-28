#!/system/bin/sh
# LibrePods — revert the HyperOS AAP channel fix on module uninstall.
# Restores the pristine fc_support_airpods.json we backed up at service.sh time.

JSON_DIR=/data/user_de/0/com.xiaomi.bluetooth/files/fc_resources
JSON=$(find "$JSON_DIR" -maxdepth 2 -name fc_support_airpods.json 2>/dev/null | head -n1)
[ -n "$JSON" ] || exit 0

BAK="$JSON.bak_librepods"
[ -f "$BAK" ] || exit 0

chattr -i "$JSON" 2>/dev/null

# Same f2fs rule as service.sh: write a new file, rename over the old.
TMP="$JSON.restore"
cp -a "$BAK" "$TMP"
chown bluetooth:bluetooth "$TMP"
chmod 600 "$TMP"
chcon u:object_r:bluetooth_data_file:s0 "$TMP" 2>/dev/null
mv "$TMP" "$JSON"

# Leave the file un-immutable so the adapter can manage it normally again.
am force-stop com.xiaomi.bluetooth 2>/dev/null
