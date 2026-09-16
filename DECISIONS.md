# Decision Log

## 1. How did you handle the concurrency race condition?

The wallet row is read using a JPA `PESSIMISTIC_WRITE` lock inside a Spring `@Transactional` service method. This causes concurrent transactions for the same wallet to serialize at the database level. Each request therefore sees the latest committed balance before checking whether the debit is affordable.

The debit is only applied when the current balance is greater than or equal to the requested amount. This prevents the balance from becoming negative.

## 2. Where did your AI assistant give you an incorrect or sub-optimal suggestion?

An initial implementation relied only on a "find transaction by transactionId" check for idempotency. That check alone is not sufficient under concurrency because multiple requests can observe that the transaction does not exist before any of them inserts it.

The implementation was improved by adding a database-level unique constraint on `transactionId` and using `saveAndFlush()` so the uniqueness constraint is enforced during the transaction. The integration test also sends three identical transaction IDs concurrently rather than testing only sequential duplicate requests.

## Trade-off

Pessimistic locking reduces concurrency for requests targeting the same wallet, but correctness is more important for a transaction ledger because two simultaneous debits must never spend the same balance.
