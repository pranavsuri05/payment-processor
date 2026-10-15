# Idempotent Payment & Wallet Event Processor

[![Java](https://img.shields.io/badge/Java-17-orange.svg)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.5-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Database](https://img.shields.io/badge/Database-H2%20In--Memory-blue.svg)](https://www.h2database.com/)

A concurrent, idempotent payment processing engine built with **Java 17**, **Spring Boot**, **Spring Data JPA**, and an **H2 in-memory database**.

It handles wallet operations (credit and debit), guarantees that a transaction is applied exactly once even under concurrent duplicate requests, and uses database-level pessimistic locking to prevent race conditions and double-spending.

---

## Contents

1. [Quick Start](#-quick-start)
2. [Run in VS Code](#-run-in-vs-code)
3. [Try the API](#-try-the-api)
4. [API Reference](#-api-reference)
5. [Running Tests](#-running-tests)
6. [Architecture](#-architecture)
7. [Project Structure](#-project-structure)
8. [Troubleshooting](#-troubleshooting)
9. [Limitations](#-limitations)

---

## 🚀 Quick Start

### Prerequisites

| Tool | Required version | Check with |
| :--- | :--- | :--- |
| JDK | 17 | `java -version` |
| Apache Maven | 3.8 or newer | `mvn -v` |

Install on macOS with Homebrew if needed:

```bash
brew install openjdk@17
```

```bash
brew install maven
```

### 1. Get the code

```bash
git clone https://github.com/<your-user>/<your-repo>.git
```

```bash
cd <your-repo>
```

### 2. Run the tests

```bash
mvn clean test
```

Expected result: `Tests run: 7, Failures: 0, Errors: 0` and `BUILD SUCCESS`.

### 3. Start the application

```bash
mvn spring-boot:run
```

Wait for a line containing `Started PaymentProcessorApplication`. The server listens on **http://localhost:8080**.

### 4. Open the API docs

Open **http://localhost:8080/swagger-ui.html** in your browser. You can try every endpoint from there with the **Try it out** button.

### 5. Stop the application

Press **Ctrl+C** in the terminal where it's running.

---

## 🧑‍💻 Run in VS Code

1. **File → Open Folder** and select the project folder.
2. Install the **Extension Pack for Java** (Microsoft) if VS Code asks.
3. Open a terminal with **Terminal → New Terminal** and run the commands from the Quick Start above:

   ```bash
   mvn clean test
   ```

   ```bash
   mvn spring-boot:run
   ```

4. Stop the app with **Ctrl+C** in that terminal.

---

## 🧪 Try the API

Start the app first (step 3 above), then run these in a second terminal.

**Create a wallet with 500.00:**

```bash
curl -X POST "http://localhost:8080/api/v1/wallets?userId=22222222-2222-2222-2222-222222222222&balance=500.00"
```

Expected response: `{"userId":"22222222-2222-2222-2222-222222222222","balance":500.00}`

**Debit 100.00 from that wallet:**

```bash
curl -X POST http://localhost:8080/api/v1/transactions/process -H "Content-Type: application/json" -d '{"transactionId":"11111111-1111-1111-1111-111111111111","userId":"22222222-2222-2222-2222-222222222222","amount":100.00,"type":"DEBIT"}'
```

Expected response: the same `transactionId` with `"balance":400.00`.

**Repeat the exact same debit.** The balance stays at 400.00 because the transaction is idempotent.

> Data is stored in memory. Restarting the app clears all wallets and transactions, so create the wallet again after a restart.

---

## 📡 API Reference

Base path: `/api/v1`. All amounts are decimals with at most 2 decimal places.

### 1. Create a wallet

Creates a wallet for a user with an initial balance.

- **URL**: `/api/v1/wallets`
- **Method**: `POST`
- **Query parameters**:
  - `userId` (UUID, required): the user's identifier.
  - `balance` (decimal, required): initial balance. Must be `0` or greater, with at most 2 decimal places.

**Response (`200 OK`)**

```json
{
  "userId": "22222222-2222-2222-2222-222222222222",
  "balance": 500.00
}
```

**Error responses**

| Status | When |
| :--- | :--- |
| `409 CONFLICT` | A wallet already exists for `userId`. The existing balance is left unchanged. |
| `400 BAD REQUEST` | `balance` is negative, has more than 2 decimal places, or is missing or malformed. |

---

### 2. Process a transaction

Applies a debit or credit to a wallet. Processing is idempotent: sending the same `transactionId` again returns the stored result without changing the balance.

- **URL**: `/api/v1/transactions/process`
- **Method**: `POST`
- **Headers**: `Content-Type: application/json`

**Request body**

| Field | Type | Description |
| :--- | :--- | :--- |
| `transactionId` | UUID | Unique ID used for idempotency. |
| `userId` | UUID | The wallet's user ID. |
| `amount` | decimal | Must be at least `0.01`, with at most 2 decimal places. |
| `type` | string | `DEBIT` or `CREDIT`. |

**Example request body**

```json
{
  "transactionId": "11111111-1111-1111-1111-111111111111",
  "userId": "22222222-2222-2222-2222-222222222222",
  "amount": 100.00,
  "type": "DEBIT"
}
```

**Success response (`200 OK`)**

```json
{
  "transactionId": "11111111-1111-1111-1111-111111111111",
  "userId": "22222222-2222-2222-2222-222222222222",
  "amount": 100.00,
  "type": "DEBIT",
  "balance": 400.00
}
```

**Error responses**

| Status | When |
| :--- | :--- |
| `409 CONFLICT` | `DEBIT` exceeds the current balance (`"Insufficient funds"`). |
| `404 NOT FOUND` | No wallet exists for `userId` (`"Wallet not found"`). |
| `400 BAD REQUEST` | Missing fields, `amount` below `0.01`, more than 2 decimal places, invalid `type`, or malformed JSON. |
| `405 METHOD NOT ALLOWED` | Using a method other than `POST`. |

---

## 🏗️ Architecture

### 1. Concurrency control (pessimistic locking)

To stop two simultaneous debits from overspending a wallet, the wallet row is locked while it's being changed:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select w from Wallet w where w.userId = :userId")
Optional<Wallet> findByUserIdForUpdate(@Param("userId") UUID userId);
```

- The wallet is fetched with `PESSIMISTIC_WRITE` inside a `@Transactional` method, which issues `SELECT ... FOR UPDATE`.
- Concurrent requests for the same wallet are serialized, so each one sees the latest committed balance.
- A debit is applied only if the balance covers it, so wallet balances never go negative.

### 2. Idempotency

- **Lookup**: the service first checks whether `transactionId` already exists and returns the stored result if it does.
- **Unique constraint**: `transactionId` is `UNIQUE` in the database. `saveAndFlush()` enforces it during the transaction, so a duplicate that races past the lookup cannot be stored twice.
- **Retry on conflict**: if a concurrent duplicate loses that race, the controller retries once. The winning request has already committed, so the retry returns its stored result with HTTP `200`.

See [AUDIT_REPORT.md](AUDIT_REPORT.md) for the audit that led to the current behaviour.

---

## 📂 Project Structure

```text
Payment-Processor-Project/
├── pom.xml
├── README.md
├── AUDIT_REPORT.md
├── DECISIONS.md
└── src/
    ├── main/
    │   ├── java/com/example/paymentprocessor/
    │   │   ├── PaymentProcessorApplication.java   # Spring Boot entry point
    │   │   ├── controller/
    │   │   │   ├── TransactionController.java    # POST /api/v1/transactions/process
    │   │   │   ├── WalletController.java         # POST /api/v1/wallets
    │   │   │   └── GlobalExceptionHandler.java   # Maps exceptions to HTTP errors
    │   │   ├── dto/
    │   │   │   ├── TransactionRequest.java       # Request body with validation
    │   │   │   └── TransactionResponse.java      # Response record
    │   │   ├── entity/
    │   │   │   ├── Wallet.java                   # Wallet entity (userId, balance)
    │   │   │   ├── Transaction.java              # Transaction entity (unique transactionId)
    │   │   │   └── TransactionType.java          # DEBIT, CREDIT
    │   │   ├── exception/
    │   │   │   ├── InsufficientFundsException.java
    │   │   │   └── WalletNotFoundException.java
    │   │   ├── repository/
    │   │   │   ├── WalletRepository.java         # Includes the pessimistic lock query
    │   │   │   └── TransactionRepository.java
    │   │   └── service/
    │   │       └── TransactionService.java       # Idempotent transaction logic
    │   └── resources/
    │       └── application.properties
    └── test/
        └── java/com/example/paymentprocessor/
            ├── TransactionProcessorIntegrationTest.java  # Concurrency and idempotency tests
            └── ApiRegressionTest.java                    # HTTP-level regression tests
```

---

## 🧪 Running Tests

```bash
mvn test
```

Run only the regression tests:

```bash
mvn test -Dtest=ApiRegressionTest
```

### Test cases

**`TransactionProcessorIntegrationTest`**

1. **`happyPath()`**: a debit reduces the wallet balance correctly.
2. **`idempotency()`**: sends 3 identical `transactionId` requests concurrently. Verifies the balance is deducted **once** and exactly **1** transaction is stored.
3. **`raceCondition()`**: sends 10 concurrent ₹100 debits against a ₹500 wallet. Verifies **5 succeed**, **5 fail with insufficient funds**, and the final balance is **₹0.00**.

**`ApiRegressionTest`**

1. Creating a wallet for an existing user returns `409` and leaves the balance unchanged.
2. A negative starting balance is rejected with `400`.
3. An amount with more than 2 decimal places is rejected with `400`, and nothing is recorded.
4. 10 concurrent requests with the same `transactionId` all return `200`, and the balance is deducted once.

---

## 🛠️ Troubleshooting

| Problem | Fix |
| :--- | :--- |
| `mvn: command not found` | Install Maven (`brew install maven`) and reopen the terminal. |
| `java: command not found` or wrong version | Install JDK 17 and check `java -version` shows 17. |
| `Port 8080 was already in use` | Stop the other process: `lsof -ti:8080 \| xargs kill`, then start again. |
| `Wallet not found` after a restart | Data is in memory and resets on restart. Create the wallet again. |
| `Wallet already exists` (409) | Expected. Use a new `userId`, or restart the app to clear data. |
| Lots of `ERROR` lines in the log about `Unique index or primary key violation` | Expected during concurrent duplicate requests. They are caught and retried, and the API still returns `200`. |

---

## ⚠️ Limitations

- **In-memory database**: all data is lost when the app stops.
- **No authentication**: any client can create wallets and process transactions. Intended for local development and demonstration only.
- **H2 console is enabled** at http://localhost:8080/h2-console (JDBC URL `jdbc:h2:mem:paymentdb`, user `sa`, empty password). Do not expose port 8080 to a network.
- **No Maven Wrapper** in the repository. Use the system `mvn` as shown above.
- **Same `transactionId` with a different payload** returns the originally stored transaction without an error.

---

## 📜 Design Decisions

For the reasoning behind pessimistic versus optimistic locking, see [DECISIONS.md](DECISIONS.md).
