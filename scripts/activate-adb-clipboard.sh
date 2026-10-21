#!/system/bin/sh
# Run on Android 11 as the authorized ADB shell. No ongoing ADB connection is needed.
set -eu
[ "$(id -u)" = 2000 ] || { echo "Use adb shell"; exit 1; }
package=com.floatclip.probe
apk=$(pm path "$package" | sed -n 's/^package://p' | head -n 1)
uid=$(cmd package list packages -U "$package" | sed -n 's/.*uid://p' | head -n 1)
[ -n "$apk" ] && [ -n "$uid" ] || { echo "Install FloatClip first"; exit 1; }
# Stop only a verified previous FloatClip helper, never a reused unrelated PID.
pidfile=/data/local/tmp/floatclip-capture.pid
if [ -f "$pidfile" ]; then
  previous=$(cat "$pidfile")
  case "$previous" in *[!0-9]*|"") ;; *)
    if tr '\000' ' ' < "/proc/$previous/cmdline" 2>/dev/null | grep -q com.floatclip.app.capture.AdbClipboardHelper; then
      kill "$previous" 2>/dev/null || true
    fi ;;
  esac
fi
if [ -f /data/local/tmp/floatclip-capture.jar ]; then export CLASSPATH=/data/local/tmp/floatclip-capture.jar; else export CLASSPATH="$apk"; fi
nohup app_process /system/bin com.floatclip.app.capture.AdbClipboardHelper "$uid" </dev/null >/data/local/tmp/floatclip-capture.log 2>&1 &
echo $! > "$pidfile"
echo "FloatClip capture helper started. Enable ADB background capture in FloatClip. Reactivate after phone restart."
