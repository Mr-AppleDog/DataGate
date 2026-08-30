#!/usr/bin/env bash
# DataGate local-development KEK bootstrap (CRED-003).
# Creates a repository-external AES-256 KEK. Existing files are validated and never overwritten.
set -euo pipefail

if [[ $# -gt 1 ]]; then
  echo "Usage: $0 [kek-file]" >&2
  exit 2
fi

user_profile_dir="${HOME:?Cannot resolve the current user home directory}"
kek_path="${1:-${DATAGATE_KEK_FILE:-${user_profile_dir}/.datagate/kek.txt}}"

require_openssl() {
  if ! command -v openssl >/dev/null 2>&1; then
    echo "OpenSSL is required to generate and validate the development KEK." >&2
    exit 1
  fi
}

validate_kek() {
  local path="$1"
  local first_key_line
  local version
  local encoded_key
  local decoded_length

  first_key_line="$(awk 'NF && $1 !~ /^#/ { print; exit }' "$path")"
  if [[ -z "$first_key_line" || "$first_key_line" != *:* ]]; then
    echo "Existing KEK file has invalid format (expected version:BASE64); it was not overwritten." >&2
    exit 1
  fi
  version="${first_key_line%%:*}"
  encoded_key="${first_key_line#*:}"
  if [[ -z "$version" || -z "$encoded_key" ]]; then
    echo "Existing KEK file has an empty version or key; it was not overwritten." >&2
    exit 1
  fi
  decoded_length="$(printf '%s' "$encoded_key" | openssl base64 -d -A | wc -c | tr -d '[:space:]')"
  if [[ "$decoded_length" != "32" ]]; then
    echo "Existing KEK must decode to exactly 32 bytes; it was not overwritten." >&2
    exit 1
  fi
}

require_openssl
if [[ -e "$kek_path" ]]; then
  validate_kek "$kek_path"
  chmod 600 "$kek_path"
  echo "DataGate development KEK already exists and is valid: $kek_path"
  exit 0
fi

kek_directory="$(dirname "$kek_path")"
mkdir -p "$kek_directory"
umask 077
temporary_kek="$(mktemp "${kek_path}.tmp.XXXXXX")"
cleanup() {
  if [[ -n "${temporary_kek:-}" && -e "$temporary_kek" ]]; then
    rm -f -- "$temporary_kek"
  fi
}
trap cleanup EXIT

encoded_key="$(openssl rand -base64 32 | tr -d '\r\n')"
printf 'v1:%s\n' "$encoded_key" > "$temporary_kek"
unset encoded_key
chmod 600 "$temporary_kek"
mv -- "$temporary_kek" "$kek_path"
temporary_kek=''

validate_kek "$kek_path"
echo "Created DataGate development KEK outside the repository: $kek_path"
echo "Do not commit or share this file. Production must use a separately managed read-only Secret."
