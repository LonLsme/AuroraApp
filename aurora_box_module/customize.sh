#!/system/bin/sh

PERSISTENT_DIR="/data/adb/aurora_kernel_spoof"
LEGACY_PERSISTENT_DIR="/data/adb/violet_kernel_spoof"
DEFAULT_CONFIG="$MODPATH/config.sh"
DEST_BIN_DIR="/data/adb/ksu/bin"
MODULE_BIN="$MODPATH/tools/ksu_susfs_arm64"
LEGACY_MODULE_DIRS="/data/adb/modules/violet_box_module /data/adb/modules_update/violet_box_module"

ui_print " "
ui_print "***************************************"
ui_print "  Aurora Core Extension"
ui_print "***************************************"
ui_print " "

mkdir -p "$PERSISTENT_DIR"

# 改名迁移：旧版配置搬到新路径，避免用户已填的伪装值丢失
if [ ! -f "$PERSISTENT_DIR/config.sh" ] && [ -f "$LEGACY_PERSISTENT_DIR/config.sh" ]; then
  cp -af "$LEGACY_PERSISTENT_DIR/config.sh" "$PERSISTENT_DIR/config.sh"
  ui_print "- 已从 $LEGACY_PERSISTENT_DIR 迁移配置"
fi

# 改名迁移：清掉旧 id 的模块目录，否则新旧两个模块会在开机阶段互相覆盖
for legacy_dir in $LEGACY_MODULE_DIRS; do
  if [ -d "$legacy_dir" ]; then
    rm -rf "$legacy_dir"
    ui_print "- 已移除旧模块目录 $legacy_dir"
  fi
done

if [ -d "$DEST_BIN_DIR" ] && [ -f "$MODULE_BIN" ]; then
  cp -af "$MODULE_BIN" "$DEST_BIN_DIR/ksu_susfs"
  chmod 0755 "$DEST_BIN_DIR/ksu_susfs"
  ui_print "- 已安装匹配版 ksu_susfs 到 $DEST_BIN_DIR/ksu_susfs"
else
  ui_print "! 未找到可安装的 ksu_susfs 二进制，跳过覆盖"
fi

if [ ! -f "$PERSISTENT_DIR/config.sh" ]; then
  cp -af "$DEFAULT_CONFIG" "$PERSISTENT_DIR/config.sh"
  ui_print "- 初始化配置: $PERSISTENT_DIR/config.sh"
else
  # 仅补齐新键，保留用户已有值
  while IFS= read -r line; do
    key="$(echo "$line" | cut -d'=' -f1)"
    [ -z "$key" ] && continue
    grep -q "^${key}=" "$PERSISTENT_DIR/config.sh" || echo "$line" >> "$PERSISTENT_DIR/config.sh"
  done < "$DEFAULT_CONFIG"
  ui_print "- 保留现有配置并补齐缺失键"
fi

chmod 0644 "$PERSISTENT_DIR/config.sh"
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/post-fs-data.sh" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
set_perm "$MODPATH/tools/ksu_susfs_arm64" 0 0 0755
