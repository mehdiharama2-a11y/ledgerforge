# Security model

LedgerForge is a multi-tenant financial ledger. Tenant identity comes only from a signed JWT, never from request parameters. Financial writes require `ADMIN` or `OPERATOR`; auditors are read-only.

Implemented controls include issuer-validated 30-minute JWTs, 32-byte minimum signing secrets, exact CORS origins, strict browser CSP, in-memory-only access tokens, request/body limits, per-IP login/webhook throttling, constant-time HMAC verification, timestamp tolerance, webhook idempotency, and organization-scoped reconciliation queries.

Production requires HTTPS, an edge proxy with rate limits, a randomly generated `JWT_SECRET`, rotated per-organization webhook secrets, private database networking, non-root containers, centralized audit alerts, and a secret manager. Do not expose seeded demo credentials or defaults outside localhost.
