#!/usr/bin/env bash
set -euo pipefail
kubectl label node k3s-cp lab-role=control --overwrite
kubectl label node k3s-worker1 k3s-worker2 lab-role=worker --overwrite
# Switch ServiceLB to worker-only allow-list mode.
kubectl label node k3s-worker1 k3s-worker2 svccontroller.k3s.cattle.io/enablelb=true --overwrite
kubectl get nodes --show-labels
