#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
java_home=${JAVA_HOME:-$root/.toolchains/jdk17}
key_dir="$root/.toolchains/nearby-probe"
mkdir -p "$key_dir"
if [[ ! -f "$key_dir/debug.keystore" ]]; then
  umask 077
  "$java_home/bin/keytool" -genkeypair -noprompt -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 3650 \
    -keystore "$key_dir/debug.keystore" -storepass android -keypass android \
    -dname "CN=FloatClip P0 Debug,O=FloatClip,C=CN" >/dev/null
fi
chmod 600 "$key_dir/debug.keystore"
