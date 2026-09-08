#!/usr/bin/env bash
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
tools="$root/.toolchains"
jdk="$tools/jdk17"
bundle=${SSL_CERT_FILE:-}
out="$tools/java-cacerts"
tmp="$tools/java-ca-split"

[[ -x "$jdk/bin/keytool" ]] || { echo "JDK keytool missing" >&2; exit 69; }
[[ -n "$bundle" && -f "$bundle" ]] || { echo "SSL_CERT_FILE bundle missing" >&2; exit 70; }

rm -rf "$tmp" "$out"
mkdir -p "$tmp"
python3 - "$bundle" "$tmp" <<'PY'
from pathlib import Path
import sys
src=Path(sys.argv[1]).read_text()
out=Path(sys.argv[2])
parts=src.split('-----END CERTIFICATE-----')
n=0
for part in parts:
    if '-----BEGIN CERTIFICATE-----' not in part:
        continue
    cert=part[part.index('-----BEGIN CERTIFICATE-----'):].strip()+'\n-----END CERTIFICATE-----\n'
    (out/f'{n:03d}.pem').write_text(cert)
    n+=1
print(n)
PY

count=0
for cert in "$tmp"/*.pem; do
  alias_name="ca-$count"
  "$jdk/bin/keytool" -importcert -noprompt -storetype JKS -keystore "$out" -storepass changeit \
    -alias "$alias_name" -file "$cert" >/dev/null 2>&1
  count=$((count+1))
done
rm -rf "$tmp"
printf 'created %s with %d certificates\n' "$out" "$count"
