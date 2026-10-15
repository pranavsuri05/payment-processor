package com.example.paymentprocessor;

import com.example.paymentprocessor.entity.*;
import com.example.paymentprocessor.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ApiRegressionTest {

    @Autowired MockMvc mockMvc;
    @Autowired WalletRepository walletRepository;
    @Autowired TransactionRepository transactionRepository;

    @BeforeEach
    void setup() {
        transactionRepository.deleteAll();
        walletRepository.deleteAll();
    }

    @Test
    @DisplayName("Creating a wallet for an existing user must not overwrite its balance.")
    void createWalletForExistingUserDoesNotOverwriteBalance() throws Exception {
        UUID user = UUID.randomUUID();
        walletRepository.save(new Wallet(user, new BigDecimal("500.00")));

        mockMvc.perform(post("/api/v1/wallets")
                        .param("userId", user.toString())
                        .param("balance", "1.00"))
                .andExpect(status().isConflict());

        assertEquals(new BigDecimal("500.00"), walletRepository.findById(user).orElseThrow().getBalance());
    }

    @Test
    @DisplayName("Creating a wallet with a negative balance must be rejected.")
    void createWalletWithNegativeBalanceIsRejected() throws Exception {
        UUID user = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/wallets")
                        .param("userId", user.toString())
                        .param("balance", "-5.00"))
                .andExpect(status().isBadRequest());

        assertTrue(walletRepository.findById(user).isEmpty());
    }

    @Test
    @DisplayName("Amounts with more than two decimal places must be rejected so the ledger matches the wallet.")
    void subCentTransactionAmountIsRejected() throws Exception {
        UUID user = UUID.randomUUID();
        walletRepository.save(new Wallet(user, new BigDecimal("100.00")));

        mockMvc.perform(processRequest(UUID.randomUUID(), user, "1.005"))
                .andExpect(status().isBadRequest());

        assertEquals(new BigDecimal("100.00"), walletRepository.findById(user).orElseThrow().getBalance());
        assertEquals(0, transactionRepository.count());
    }

    @Test
    @DisplayName("Concurrent identical transactionIDs must all return a successful idempotent response, with the balance deducted once.")
    void concurrentDuplicateTransactionReturnsSuccessForEveryRequest() throws Exception {
        UUID user = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        walletRepository.save(new Wallet(user, new BigDecimal("500.00")));

        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                start.await();
                MvcResult result = mockMvc.perform(processRequest(transactionId, user, "100.00")).andReturn();
                return result.getResponse().getStatus();
            }));
        }

        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> future : futures) statuses.add(future.get());
        executor.shutdown();

        assertTrue(statuses.stream().allMatch(s -> s == 200), "All duplicate requests should return 200, got " + statuses);
        assertEquals(new BigDecimal("400.00"), walletRepository.findById(user).orElseThrow().getBalance());
        assertEquals(1, transactionRepository.count());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder processRequest(
            UUID transactionId, UUID user, String amount) {
        return post("/api/v1/transactions/process")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"transactionId\":\"" + transactionId + "\",\"userId\":\"" + user
                        + "\",\"amount\":" + amount + ",\"type\":\"DEBIT\"}");
    }
}
