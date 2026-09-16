package com.example.paymentprocessor.service;

import com.example.paymentprocessor.dto.*;
import com.example.paymentprocessor.entity.*;
import com.example.paymentprocessor.exception.*;
import com.example.paymentprocessor.repository.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
public class TransactionService {
    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;

    public TransactionService(WalletRepository walletRepository, TransactionRepository transactionRepository) {
        this.walletRepository = walletRepository;
        this.transactionRepository = transactionRepository;
    }

    @Transactional
    public TransactionResponse process(TransactionRequest request) {
        var existing = transactionRepository.findByTransactionId(request.getTransactionId());
        if (existing.isPresent()) {
            return responseFor(existing.get());
        }

        Wallet wallet = walletRepository.findByUserIdForUpdate(request.getUserId())
                .orElseThrow(WalletNotFoundException::new);

        if (request.getType() == TransactionType.DEBIT) {
            if (wallet.getBalance().compareTo(request.getAmount()) < 0) {
                throw new InsufficientFundsException();
            }
            wallet.setBalance(wallet.getBalance().subtract(request.getAmount()));
        } else {
            wallet.setBalance(wallet.getBalance().add(request.getAmount()));
        }

        Transaction saved = transactionRepository.saveAndFlush(
                new Transaction(request.getTransactionId(), request.getUserId(), request.getAmount(), request.getType())
        );
        walletRepository.save(wallet);
        return new TransactionResponse(saved.getTransactionId(), saved.getUserId(),
                saved.getAmount(), saved.getType().name(), wallet.getBalance());
    }

    private TransactionResponse responseFor(Transaction t) {
        Wallet wallet = walletRepository.findById(t.getUserId())
                .orElseThrow(WalletNotFoundException::new);
        return new TransactionResponse(t.getTransactionId(), t.getUserId(),
                t.getAmount(), t.getType().name(), wallet.getBalance());
    }
}
