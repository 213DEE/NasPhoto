#!/system/bin/sh
# 把 NAS 连接配置写进 App 的 SharedPreferences（省去手动输入密码）
PKG=cn.dsr213.nasphoto
DIR=/data/data/$PKG/shared_prefs

UID_APP=$(dumpsys package $PKG | grep -o 'userId=[0-9]*' | head -1 | cut -d= -f2)
if [ -z "$UID_APP" ]; then
  echo "ERR: 拿不到 $PKG 的 uid"
  exit 1
fi

mkdir -p "$DIR"
cp /data/local/tmp/nasphoto.xml "$DIR/nasphoto.xml"
chown "$UID_APP:$UID_APP" "$DIR" "$DIR/nasphoto.xml"
chmod 771 "$DIR"
chmod 660 "$DIR/nasphoto.xml"

echo "UID_APP=$UID_APP"
ls -la "$DIR"
echo "--- 内容 ---"
cat "$DIR/nasphoto.xml"
