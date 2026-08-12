#!/usr/bin/env bash
# Creates the voicebridge-secrets Kubernetes Secret with real, generated values.
# Run BEFORE `kubectl apply -f infra/k8s/`:
#
#   SERVICE_TOKEN=<your-token> DATABASE_URL=<your-dsn> ./infra/k8s/create-secret.sh
#
# The template file (examples/secret-template.yaml) must never be applied
# directly — its placeholder values would become the production credential.
set -euo pipefail

SERVICE_TOKEN="${SERVICE_TOKEN:-}"
DATABASE_URL="${DATABASE_URL:-}"

if [[ -z "$SERVICE_TOKEN" || -z "$DATABASE_URL" ]]; then
  echo "Usage: SERVICE_TOKEN=<token> DATABASE_URL=<dsn> $0" >&2
  echo "Generate a token with: openssl rand -hex 32" >&2
  exit 1
fi

if [[ "$SERVICE_TOKEN" == "<replace-with-secure-token>" ]]; then
  echo "Refusing: SERVICE_TOKEN still contains the placeholder value." >&2
  exit 1
fi

kubectl create secret generic voicebridge-secrets \
  --namespace voicebridge \
  --from-literal="SERVICE_TOKEN=${SERVICE_TOKEN}" \
  --from-literal="DATABASE_URL=${DATABASE_URL}" \
  --dry-run=client -o yaml | kubectl apply -f -

echo "Secret voicebridge-secrets created in namespace voicebridge."
