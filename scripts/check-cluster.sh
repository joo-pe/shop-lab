#!/usr/bin/env bash
set -euo pipefail
kubectl config current-context
kubectl get nodes -o wide
kubectl -n shop get deployment,statefulset,pod,svc,ingress,pvc,pdb -o wide
kubectl -n shop get endpointslice
kubectl -n kube-system get pods -l app.kubernetes.io/name=traefik -o wide
kubectl -n shop get events --sort-by=.metadata.creationTimestamp
kubectl top nodes || true
kubectl top pods -n shop || true
