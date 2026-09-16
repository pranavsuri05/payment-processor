# Idempotent Payment & Wallet Event Processor

[![Java](https://img.shields.io/badge/Java-17-orange.svg)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.5-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Database](https://img.shields.io/badge/Database-H2%20In--Memory-blue.svg)](https://www.h2database.com/)

A high-performance, concurrent, and idempotent payment processing engine built with **Java 17**, **Spring Boot**, **Spring Data JPA**, and **H2 In-Memory Database**. 

This system handles core wallet operations (credit/debit), guarantees idempotent event ingestion under heavy concurrent traffic, and uses database-level pessimistic locking to prevent race conditions and double-spending.

---

## 🚀 Features

- **Idempotent Transaction Ingestion**: Prevents duplicate transaction processing even under concurrent requests using unique transaction ID constraints and transactional lookups.
- **Race-Condition & Double-Spend Safety**: Employs JPA `PESSIMISTIC_WRITE` row-level locks (`SELECT ... FOR UPDATE`) on wallet entities during balance modifications.
- **Supported Transaction Types**:
  - `DEBIT`: Deducts funds atomically after checking sufficient balance.
  - `CREDIT`: Adds funds atomically to the user's wallet.
- **RESTful API**: Clean API endpoints for wallet management and transaction processing.
- **Robust Exception Handling**: Custom exception responses (`409 Conflict` for insufficient funds, `404 Not Found` for invalid wallet IDs).
- **Concurrency & Integration Test Suite**: Multi-threaded integration tests with JUnit 5 and `ExecutorService` verifying real-world concurrent race conditions.

---

## 🛠️ Tech Stack & Prerequisites

### Tech Stack
- **Language**: Java 17
- **Framework**: Spring Boot 3.5.5 (Spring Web, Spring Data JPA, Spring Validation)
- **Database**: H2 (In-Memory Database)
- **Build Tool**: Apache Maven
- **Testing**: JUnit 5, Java Concurrency Utilities (`CountDownLatch`, `ExecutorService`, `AtomicInteger`)

### Prerequisites
- **JDK**: Java 17 or higher
- **Build Tool**: Apache Maven 3.8+ (or Maven integrated in IntelliJ IDEA / Eclipse / VS Code)

---

## 🏗️ Architecture & Technical Design

### 1. Concurrency Control (Pessimistic Locking)
To prevent race conditions where two simultaneous debit requests exceed the available wallet balance, the application uses **Database-Level Pessimistic Locking**:
```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select w from Wallet w where w.userId = :userId")
Optional<Wallet> findByUserIdForUpdate(@Param("userId") UUID userId);
```
- When a transaction is processed, the wallet row is fetched using `PESSIMISTIC_WRITE` inside a `@Transactional` block.
- Database execution issues a `SELECT ... FOR UPDATE` query, serializing all concurrent debit/credit requests targeting the same wallet.
- Each request reads the latest committed balance before applying deductions, guaranteeing that wallet balances never go negative.

### 2. Idempotency Architecture
- **Dual-Check Strategy**: Checks if `transactionId` exists before attempting execution.
- **Database Unique Constraint**: `transactionId` has a `UNIQUE` constraint at the database layer. Combined with `saveAndFlush()`, duplicate concurrent requests trigger data integrity protection, guaranteeing a transaction is executed exactly once.
- **Idempotent Response**: Repeated requests with an already-processed `transactionId` safely return the existing transaction record with current wallet balance.

---

## 📂 Project Structure

```text
payment-processor/
├── pom.xml
├── README.md
├── DECISIONS.md
└── src/
    ├── main/
    │   ├── java/com/example/paymentprocessor/
    │   │   ├── PaymentProcessorApplication.java   # Spring Boot Entry Point
    │   │   ├── controller/
    │   │   │   ├── TransactionController.java    # POST /api/v1/transactions/process
    │   │   │   ├── WalletController.java         # POST /api/v1/wallets
    │   │   │   └── GlobalExceptionHandler.java   # Centralized HTTP error handling
    │   │   ├── dto/
    │   │   │   ├── TransactionRequest.java       # Request DTO with validations
    │   │   │   └── TransactionResponse.java      # Response DTO record
    │   │   ├── entity/
    │   │   │   ├── Wallet.java                   # Wallet JPA entity (userId, balance)
    │   │   │   ├── Transaction.java              # Transaction JPA entity (unique transactionId)
    │   │   │   └── TransactionType.java          # Enum (DEBIT, CREDIT)
    │   │   ├── exception/
    │   │   │   ├── InsufficientFundsException.java
    │   │   │   └── WalletNotFoundException.java
    │   │   ├── repository/
    │   │   │   ├── WalletRepository.java         # Includes pessimistic lock query
    │   │   │   └── TransactionRepository.java
    │   │   └── service/
    │   │       └── TransactionService.java       # Core idempotent transaction business logic
    │   └── resources/
    │       └── application.properties
    └── test/
        └── java/com/example/paymentprocessor/
            └── TransactionProcessorIntegrationTest.java # Concurrency & Idempotency tests
```

---

## 📡 API Reference

### 1. Create a Wallet
Creates a new wallet for a user with an initial balance.

- **URL**: `/api/v1/wallets`
- **Method**: `POST`
- **Query Parameters**:
  - `userId` (UUID, required): Unique identifier for the user.
  - `balance` (BigDecimal, required): Initial balance.

#### Example Request
```http
POST /api/v1/wallets?userId=22222222-2222-2222-2222-222222222222&balance=500.00 HTTP/1.1
Host: localhost:8080
```

#### Response (`200 OK`)
```json
{
  "userId": "22222222-2222-2222-2222-222222222222",
  "balance": 500.00
}
```

---

### 2. Process Transaction
Processes a debit or credit event idempotently.

- **URL**: `/api/v1/transactions/process`
- **Method**: `POST`
- **Headers**: `Content-Type: application/json`

#### Request Body Schema
| Field | Type | Description |
| :--- | :--- | :--- |
| `transactionId` | UUID | Unique ID for idempotency tracking |
| `userId` | UUID | Target user's wallet ID |
| `amount` | BigDecimal | Transaction amount (must be positive) |
| `type` | String | `DEBIT` or `CREDIT` |

#### Example Request Body
```json
{
  "transactionId": "11111111-1111-1111-1111-111111111111",
  "userId": "22222222-2222-2222-2222-222222222222",
  "amount": 100.00,
  "type": "DEBIT"
}
```

#### Success Response (`200 OK`)
```json
{
  "transactionId": "11111111-1111-1111-1111-111111111111",
  "userId": "22222222-2222-2222-2222-222222222222",
  "amount": 100.00,
  "type": "DEBIT",
  "balance": 400.00
}
```

#### Error Responses
- **`409 CONFLICT`**: Insufficient funds for `DEBIT` transaction (`"Insufficient funds"`).
- **`404 NOT FOUND`**: Wallet for `userId` does not exist (`"Wallet not found"`).
- **`400 BAD REQUEST`**: Validation errors (e.g. missing fields, negative amount).

---

## ⚡ How to Build & Run

### 1. Build the Application
```bash
mvn clean package
```

### 2. Run the Application
```bash
mvn spring-boot:run
```
The server will start on `http://localhost:8080`.

---

## 🧪 Running Tests & Concurrency Verification

Execute all integration tests:
```bash
mvn test
```

### Verified Test Cases (`TransactionProcessorIntegrationTest`):
1. **`happyPath()`**: Verifies normal debit transaction reduces wallet balance accurately.
2. **`idempotency()`**: Sends 3 identical `transactionId` requests concurrently using `ExecutorService`. Verifies that balance is deducted **only once** and database record count equals **1**.
3. **`raceCondition()`**: Sends 10 concurrent debit requests of ₹100 for a wallet with ₹500 balance. Verifies that **exactly 5 requests succeed**, **5 requests fail with Insufficient funds**, and final balance is **₹0.00**.

---

## 📜 Architectural Trade-offs & Decisions

For complete design rationale and trade-off analysis between pessimistic locking vs. optimistic locking, see [DECISIONS.md](DECISIONS.md).
