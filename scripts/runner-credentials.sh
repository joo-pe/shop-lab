#!/usr/bin/env bash
# Run as the Ubuntu administrator with admin kubeconfig, not as runner.
set -euo pipefail
umask 077
LAB_CONFIG=$(mktemp)
trap 'rm -f "$LAB_CONFIG"' EXIT
LAB_TOKEN=$(kubectl -n shop create token github-deployer --duration=24h)
LAB_CA=$(kubectl config view --raw --minify -o jsonpath='{.clusters[0].cluster.certificate-authority-data}')
cat > "$LAB_CONFIG" <<EOF
apiVersion: v1
kind: Config
clusters:
- name: lab
  cluster:
    server: https://127.0.0.1:6443
    certificate-authority-data: $LAB_CA
users:
- name: deployer
  user:
    token: $LAB_TOKEN
contexts:
- name: lab
  context:
    cluster: lab
    user: deployer
    namespace: shop
current-context: lab
EOF
sudo install -d -o runner -g runner -m 700 /home/runner/.kube
sudo install -o runner -g runner -m 600 "$LAB_CONFIG" /home/runner/.kube/config
printf 'Runner credentials refreshed. Requested lifetime: 24 hours.\n'
