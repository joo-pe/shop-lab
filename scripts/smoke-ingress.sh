#!/usr/bin/env bash
set -euo pipefail
: "${INGRESS_IP:?Set a worker private/public IP reachable from this shell}"
LAB_HOST=${LAB_HOST:-minwon.lab}
curl --fail --show-error --silent --max-time 10 --resolve "$LAB_HOST:80:$INGRESS_IP" "http://$LAB_HOST/" >/dev/null
LAB_HTTP_CODE=$(curl --silent --show-error --max-time 10 -o /dev/null -w '%{http_code}' --resolve "$LAB_HOST:80:$INGRESS_IP" "http://$LAB_HOST/api/members/me")
[ "$LAB_HTTP_CODE" = 401 ] || { echo "Unexpected member API response: $LAB_HTTP_CODE"; exit 1; }
echo 'Ingress website=200 and unauthenticated member API=401: OK'
