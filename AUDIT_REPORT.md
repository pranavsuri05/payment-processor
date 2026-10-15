# Audit Report: Idempotent Payment & Wallet Event Processor

Scope: audit of the existing project. No features were added, no architecture was changed. Only confirmed defects were fixed.

Environment: macOS (Darwin 24.5), OpenJDK 17.0.14/17.0.15, Apache Maven 3.10.0 (installed via Homebrew; the project has no Maven Wrapper), Spring Boot 3.5.5, H2 in-memory.

## 1. Baseline (before any change)

| Check | Result |
| :--- | :--- |
| `mvn clean test` | 3/3 tests passed, `BUILD SUCCESS` |
| `mvn clean package` | Built `payment-processor-0.0.1-SNAPSHOT.jar` |
| Startup | Started on port 8080 in ~2 s, no startup errors |
| Endpoint probes (valid and invalid inputs) | Correct except the items in section 2 |
| Concurrent duplicate `transactionId` (5 simultaneous identical requests) | **3 of 5 returned HTTP 500** (`DataIntegrityViolationException` leaked). Balance was deducted once. |
| Concurrent debits (15 × ₹10 against ₹100) | Correct: 10 × 200, 5 × 409, final balance 0 |

## 2. Confirmed defects

| # | Defect | Evidence | Root cause |
| :- | :--- | :--- | :--- |
| D1 | Concurrent duplicate requests with the same `transactionId` returned HTTP 500 instead of the idempotent 200 response the README promises. | 3 of 5 duplicates returned 500 in the baseline run. The integration test `idempotency()` swallowed these exceptions, so it passed anyway. | The second request passes the existence check before the first commits, then hits the `UNIQUE` constraint at `saveAndFlush`. The resulting exception is not handled. |
| D2 | `POST /api/v1/wallets` for an existing user silently overwrote the balance (500 → 1 in the baseline). | Reproduced via HTTP and by the new regression test. | `walletRepository.save()` on an entity with an assigned ID performs a merge (update), not an insert. |
| D3 | A wallet could be created with a negative balance, contrary to the README's "balances never go negative". | `balance=-5` returned 200. | No validation on the `balance` request parameter. |
| D4 | Transaction amounts with more than 2 decimal places were accepted. The response showed the unrounded balance while the database stored a rounded value. | Debit of `1.005` from ₹100 returned `balance: 98.995`. A following debit of `0.01` returned `98.99`, showing the stored balance was `99.00`. | The `amount` column is `NUMERIC(19,2)`, but the request DTO had no scale check. The in-memory balance was never rounded. |

Not a defect, but worth knowing: the `ConstraintViolationException` that the new validation raises had no handler, so it would have produced HTTP 500. It is handled as part of fix D3.

## 3. Changes made

All changes are minimal. Existing tests were not modified or weakened.

| File | Change | Fixes |
| :--- | :--- | :--- |
| `controller/TransactionController.java` | `process()` retries once when `DataIntegrityViolationException` is thrown. The winning concurrent request has already committed, so the retry finds the stored transaction and returns it. | D1 |
| `controller/WalletController.java` | Added `@Validated`, `@PositiveOrZero` and `@Digits(integer=17, fraction=2)` on `balance`. Returns 409 `Wallet already exists` if the user already has a wallet. | D2, D3, D4 (wallet side) |
| `dto/TransactionRequest.java` | Added `@Digits(integer=17, fraction=2)` to `amount`. | D4 |
| `controller/GlobalExceptionHandler.java` | Maps `ConstraintViolationException` to 400. | Required for D3 |
| `src/test/.../ApiRegressionTest.java` (new) | 4 tests: no overwrite on re-create (409), negative balance rejected, sub-cent amount rejected, 10 concurrent identical transaction IDs all return 200 with a single deduction. | Regression tests for D1–D4 |

Each regression test was run against the unmodified code first. All four failed, reproducing the defects.

## 4. Final test results

- `mvn clean test`: **7/7 passed** (3 original + 4 new), `BUILD SUCCESS`.
- Re-ran the full suite 3 more times: 7/7 passed each time, no flakiness observed.
- `mvn clean package`: `BUILD SUCCESS`.
- Fixed jar started on port 8080 and health-checked.

## 5. Endpoint verification (fixed build)

| Request | Result |
| :--- | :--- |
| Create wallet (valid) | 200 |
| Create wallet for existing user | **409**, balance unchanged |
| Create wallet with negative balance | **400** |
| Create wallet with 3-decimal balance | **400** |
| Create wallet with zero balance | 200 (allowed, see limitations) |
| Create wallet missing balance / invalid UUID | 400 |
| Debit valid / credit valid | 200, balances correct (500 → 400 → 350 → 347.50 across the probe sequence) |
| Replay same `transactionId` sequentially | 200, same response, balance not deducted again |
| Debit with insufficient funds | 409 `Insufficient funds` |
| Debit/credit on absent wallet | 404 `Wallet not found` |
| Amount 0 / negative / sub-cent | 400 |
| Invalid type / missing fields / malformed JSON | 400 |
| GET on `/transactions/process` | 405 |

## 6. Concurrency and idempotency

- **Concurrent duplicates (10 identical `transactionId`s):** all 200 (previously 3 of 5 were 500). Balance deducted exactly once (verified by a follow-up credit probe).
- **Concurrent debits (15 × ₹10 against ₹100):** 10 × 200, 5 × 409, final balance 0.00 (verified).
- Verified through HTTP responses and follow-up balance probes. **I did not run direct SQL against the H2 database.** The H2 console is reachable (section 8) if you want to inspect the rows yourself.

## 7. Not verified / limitations (not fixed, by design or out of scope)

- **No LICENSE file in the repository.** Without a license, the code is under default copyright and reuse is not granted. Confirm you have permission before publishing a copy, and add your own license in your repo.
- **H2 console is enabled** (`spring.h2.console.enabled=true`) with no authentication. Fine for local development; do not expose the port.
- **No authentication or authorization** on any endpoint. Explicitly out of scope.
- **Same `transactionId` with different payload** (different user, amount or type) returns the originally stored record silently instead of an error. This is a common idempotency design choice, but a mismatched replay could mask a client bug.
- **Wallet creation race:** the existence check and insert are not atomic. Two simultaneous creates for the same user will most likely produce a primary-key error (500) rather than an overwrite, but this was not tested.
- **Zero-balance wallets** are allowed; the fix only rejects negative balances.
- **Hibernate logs `ERROR` lines** for the unique-constraint violations on duplicate requests. These are caught and retried, so the responses are 200. The log noise was left as is.
- **README** does not yet document the new 409 (wallet exists) or the new 400 cases (negative balance, >2 decimals). Left unchanged to keep the diff minimal.
- **Swagger UI added later** via `springdoc-openapi-starter-webmvc-ui` 2.8.9 (the only dependency change after the audit). UI: `http://localhost:8080/swagger-ui.html`; OpenAPI JSON: `http://localhost:8080/v3/api-docs`. Verified: UI returns 200 after redirect, and the docs list both endpoints. The build and 7 tests still pass.
- **No Maven Wrapper** in the repository; Maven 3.8+ is required.
- Startup runs H2 with `create-drop`, so all data is lost on restart.
