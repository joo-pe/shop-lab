#!/usr/bin/env bash
# Two sequential API reads per second, finite duration; synthetic account only.
set -euo pipefail
: "${INGRESS_IP:?Set reachable worker IP}"
LAB_HOST=${LAB_HOST:-minwon.lab}
LAB_REQUESTS=${LAB_REQUESTS:-120}
[[ "$LAB_REQUESTS" =~ ^[0-9]+$ ]] && (( LAB_REQUESTS >= 1 && LAB_REQUESTS <= 600 )) || { echo 'LAB_REQUESTS must be 1..600'; exit 1; }
command -v jq >/dev/null
LAB_TOKEN=$(curl --fail --silent --show-error --max-time 10 --resolve "$LAB_HOST:80:$INGRESS_IP" \
 -H 'Content-Type: application/json' -d '{"name":"모니터링 체험"}' "http://$LAB_HOST/api/members/demo" | jq -er .token)
for ((i=1;i<=LAB_REQUESTS;i++)); do
 for path in /api/members/me /api/orders; do
  curl --silent --show-error --max-time 5 --resolve "$LAB_HOST:80:$INGRESS_IP" \
   -H "Authorization: Bearer $LAB_TOKEN" -o /dev/null -w '%{http_code} %{time_total}s\n' "http://$LAB_HOST$path" || true
 done
 sleep 1
done
unset LAB_TOKEN
