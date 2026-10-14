#!/usr/bin/env bash
set -euo pipefail
: "${IMAGE:?Set Java API image}"
: "${WEB_IMAGE:?Set Nginx website image}"
for app in member-api payment-api order-api; do
 kubectl -n shop set image "deployment/$app" "api=$IMAGE"
 kubectl -n shop rollout status "deployment/$app" --timeout=300s
done
kubectl -n shop set image deployment/gateway "nginx=$WEB_IMAGE"
kubectl -n shop rollout status deployment/gateway --timeout=180s
