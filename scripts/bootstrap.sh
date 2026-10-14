#!/usr/bin/env bash
set -euo pipefail
: "${IMAGE:?Set Java API image}"
: "${WEB_IMAGE:?Set Ingress-mode website image built with web/Dockerfile.ingress}"
kubectl -n shop get secret db-secret ghcr-pull >/dev/null
kubectl get node k3s-cp k3s-worker1 k3s-worker2 >/dev/null
kubectl apply -f k8s/db.yaml
kubectl -n shop rollout status statefulset/postgres --timeout=300s
sed "s|IMAGE_PLACEHOLDER|$IMAGE|g" k8s/apis.yaml | kubectl apply -f -
for app in member-api payment-api order-api; do
 kubectl -n shop rollout status "deployment/$app" --timeout=300s
done
sed "s|WEB_IMAGE_PLACEHOLDER|$WEB_IMAGE|g" k8s/gateway.yaml | kubectl apply -f -
kubectl -n shop rollout status deployment/gateway --timeout=180s
kubectl apply -f k8s/pdb.yaml
kubectl apply -f k8s/ingress.yaml
kubectl apply -f k8s/runner-rbac.yaml
kubectl -n shop get pods -o wide
kubectl -n shop get ingress
