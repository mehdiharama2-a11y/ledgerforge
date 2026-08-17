#!/usr/bin/env sh
set -eu
api_url="${API_URL:-http://localhost:8080}"
org="00000000-0000-4000-8000-000000000001"
key="smoke-$(date +%s)"
attempt=0
until curl --fail --silent "$api_url/actuator/health" >/dev/null; do
  attempt=$((attempt + 1))
  if [ "$attempt" -ge 30 ]; then echo "API did not become healthy" >&2; exit 1; fi
  sleep 1
done
response="$(curl --fail --silent --show-error "$api_url/api/payments" -X POST -H "X-Organization-Id: $org" -H "Idempotency-Key: $key" -H 'content-type: application/json' -d '{"amount":125.50,"currency":"USD"}')"
payment_id="$(printf '%s' "$response" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')"
test -n "$payment_id"
replay="$(curl --fail --silent --show-error "$api_url/api/payments" -X POST -H "X-Organization-Id: $org" -H "Idempotency-Key: $key" -H 'content-type: application/json' -d '{"amount":125.50,"currency":"USD"}')"
printf '%s' "$replay" | grep -q "$payment_id"
curl --fail --silent --show-error "$api_url/api/payments/$payment_id/refunds" -X POST -H "X-Organization-Id: $org" -H "Idempotency-Key: $key-refund" -H 'content-type: application/json' -d '{"amount":25.50}' >/dev/null
echo "LedgerForge payment, idempotency, and refund smoke test passed ($payment_id)"
