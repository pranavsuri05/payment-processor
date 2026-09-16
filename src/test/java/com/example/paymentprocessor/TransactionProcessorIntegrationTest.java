package com.example.paymentprocessor;

import com.example.paymentprocessor.dto.TransactionRequest;
import com.example.paymentprocessor.entity.*;
import com.example.paymentprocessor.repository.*;
import com.example.paymentprocessor.service.TransactionService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class TransactionProcessorIntegrationTest {

    @Autowired TransactionService service;
    @Autowired WalletRepository walletRepository;
    @Autowired TransactionRepository transactionRepository;

    @BeforeEach
    void setup() {
        transactionRepository.deleteAll();
        walletRepository.deleteAll();
    }

    @Test
    @DisplayName("Processes a single valid debit transaction successfully.")
    void happyPath() {
        UUID user = UUID.randomUUID();
        walletRepository.save(new Wallet(user, new BigDecimal("500.00")));

        TransactionRequest request = request(UUID.randomUUID(), user, "100.00");
        var response = service.process(request);

        assertEquals(new BigDecimal("400.00"), response.balance());
        assertEquals(new BigDecimal("400.00"), walletRepository.findById(user).orElseThrow().getBalance());
    }

    @Test
    @DisplayName("Sends 3 identical transactionIDs simultaneously. Ensures the balance is only deducted once.")
    void idempotency() throws Exception {
        UUID user = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        walletRepository.save(new Wallet(user, new BigDecimal("500.00")));

        ExecutorService executor = Executors.newFixedThreadPool(3);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < 3; i++) {
            futures.add(executor.submit(() -> {
                start.await();
                try {
                    service.process(request(transactionId, user, "100.00"));
                } catch (RuntimeException ignored) {
                    // A concurrent duplicate may race with the unique transaction constraint.
                }
                return null;
            }));
        }

        start.countDown();
        for (Future<?> future : futures) future.get();
        executor.shutdown();

        assertEquals(new BigDecimal("400.00"), walletRepository.findById(user).orElseThrow().getBalance());
        assertEquals(1, transactionRepository.count());
    }

    @Test
    @DisplayName("Sends 10 concurrent debit requests of ₹100 for a wallet with a ₹500 balance. Ensures the final balance is exactly ₹0 and 5 requests fail with insufficient funds.")
    void raceCondition() throws Exception {
        UUID user = UUID.randomUUID();
        walletRepository.save(new Wallet(user, new BigDecimal("500.00")));

        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger insufficient = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
            futures.add(executor.submit(() -> {
                start.await();
                try {
                    service.process(request(UUID.randomUUID(), user, "100.00"));
                    success.incrementAndGet();
                } catch (RuntimeException e) {
                    if (e.getMessage() != null && e.getMessage().contains("Insufficient funds")) {
                        insufficient.incrementAndGet();
                    } else {
                        throw e;
                    }
                }
                return null;
            }));
        }

        start.countDown();
        for (Future<?> future : futures) future.get();
        executor.shutdown();

        assertEquals(5, success.get());
        assertEquals(5, insufficient.get());
        assertEquals(new BigDecimal("0.00"), walletRepository.findById(user).orElseThrow().getBalance());
    }

    private TransactionRequest request(UUID transactionId, UUID user, String amount) {
        TransactionRequest r = new TransactionRequest();
        r.setTransactionId(transactionId);
        r.setUserId(user);
        r.setAmount(new BigDecimal(amount));
        r.setType(TransactionType.DEBIT);
        return r;
    }
}
