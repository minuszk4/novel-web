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

firebase_auth_domain=${FIREBASE_AUTH_DOMAIN:-}
if [ -n "$firebase_auth_domain" ] && \
  ! printf '%s\n' "$firebase_auth_domain" | grep -Eq '^[A-Za-z0-9.-]{1,253}$'; then
  echo "FIREBASE_AUTH_DOMAIN must be a hostname without scheme, path, query or fragment." >&2
  exit 1
fi
