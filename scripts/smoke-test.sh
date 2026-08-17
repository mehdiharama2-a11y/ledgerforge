#!/usr/bin/env sh
set -eu
api_url="${API_URL:-http://localhost:8080}"
attempt=0
until curl --fail --silent "$api_url/actuator/health" >/dev/null; do
  attempt=$((attempt + 1)); [ "$attempt" -lt 30 ] || { echo "API did not become healthy" >&2; exit 1; }; sleep 1
done

login="$(curl --fail --silent "$api_url/api/auth/login" -H 'content-type: application/json' -d '{"organizationSlug":"demo","email":"admin@ledgerforge.dev","password":"LedgerForge123!"}')"
token="$(printf '%s' "$login" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')"
[ -n "$token" ]
timestamp="$(date +%s)"
reference="demo-$timestamp"
sign() { printf '%s' "$timestamp.$1" | openssl dgst -sha256 -hmac 'whsec_demo_ledgerforge' | awk '{print $2}'; }

# Deliver the refund before capture to prove out-of-order handling.
refund_payload="{\"type\":\"payment.refunded\",\"paymentReference\":\"$reference\",\"amount\":10.00,\"currency\":\"USD\",\"occurredAt\":\"$(date -u +%FT%TZ)\"}"
refund_signature="$(sign "$refund_payload")"
curl --fail --silent "$api_url/api/webhooks/demo" -H "X-Webhook-Id: refund-first-$reference" -H "X-Webhook-Timestamp: $timestamp" -H "X-Webhook-Signature: $refund_signature" -H 'content-type: application/json' --data-binary "$refund_payload" | grep -q 'PENDING'

capture_payload="{\"type\":\"payment.captured\",\"paymentReference\":\"$reference\",\"amount\":100.00,\"currency\":\"USD\",\"occurredAt\":\"$(date -u +%FT%TZ)\"}"
capture_signature="$(sign "$capture_payload")"
capture="$(curl --fail --silent "$api_url/api/webhooks/demo" -H "X-Webhook-Id: capture-$reference" -H "X-Webhook-Timestamp: $timestamp" -H "X-Webhook-Signature: $capture_signature" -H 'content-type: application/json' --data-binary "$capture_payload")"
payment_id="$(printf '%s' "$capture" | sed -n 's/.*"paymentId":"\([^"]*\)".*/\1/p')"
[ -n "$payment_id" ]

# The exact duplicate must be acknowledged without another payment.
curl --fail --silent "$api_url/api/webhooks/demo" -H "X-Webhook-Id: capture-$reference" -H "X-Webhook-Timestamp: $timestamp" -H "X-Webhook-Signature: $capture_signature" -H 'content-type: application/json' --data-binary "$capture_payload" | grep -q '"duplicate":true'

# Two competing refunds cannot both consume the remaining USD 90.
tmpdir="$(mktemp -d)"; trap 'rm -rf "$tmpdir"' EXIT
curl --silent -o "$tmpdir/a" -w '%{http_code}' "$api_url/api/payments/$payment_id/refunds" -X POST -H "authorization: Bearer $token" -H "Idempotency-Key: concurrent-a-$reference" -H 'content-type: application/json' -d '{"amount":60.00}' >"$tmpdir/a.code" &
pid_a=$!
curl --silent -o "$tmpdir/b" -w '%{http_code}' "$api_url/api/payments/$payment_id/refunds" -X POST -H "authorization: Bearer $token" -H "Idempotency-Key: concurrent-b-$reference" -H 'content-type: application/json' -d '{"amount":60.00}' >"$tmpdir/b.code" &
pid_b=$!
wait "$pid_a"; wait "$pid_b"
codes="$(cat "$tmpdir/a.code") $(cat "$tmpdir/b.code")"
case "$codes" in "201 409"|"409 201") ;; *) echo "Unexpected concurrent refund statuses: $codes" >&2; exit 1;; esac

sleep 2
curl --fail --silent "$api_url/api/reconciliation/run" -X POST -H "authorization: Bearer $token" | grep -q "$payment_id"
curl --fail --silent "$api_url/api/reports/payments" -H "authorization: Bearer $token" | grep -q "$payment_id"
echo "LedgerForge v1 flow passed: signed webhook, outbox, ledger, concurrent refund, reconciliation ($payment_id)"
