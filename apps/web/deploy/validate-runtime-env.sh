#!/bin/sh
set -eu

api_upstream=${API_UPSTREAM:-}
case "$api_upstream" in
  http://*) api_authority=${api_upstream#http://} ;;
  https://*) api_authority=${api_upstream#https://} ;;
  *)
    echo "API_UPSTREAM must be an HTTP(S) origin without a path." >&2
    exit 1
    ;;
esac

if ! printf '%s\n' "$api_authority" | grep -Eq '^[A-Za-z0-9._-]+(:[0-9]{1,5})?$'; then
  echo "API_UPSTREAM must be an HTTP(S) origin without credentials, path, query or fragment." >&2
  exit 1
fi

google_callback_origin=${GOOGLE_CALLBACK_ORIGIN:-}
if [ -n "$google_callback_origin" ] && \
  ! printf '%s\n' "$google_callback_origin" | \
    grep -Eq '^https://[A-Za-z0-9.-]+(:[0-9]{1,5})?$|^http://localhost(:[0-9]{1,5})?$'; then
  echo "GOOGLE_CALLBACK_ORIGIN must be one HTTPS origin (or localhost HTTP for development)." >&2
  exit 1
fi
