package com.example.paymentprocessor.controller;

import com.example.paymentprocessor.dto.*;
import com.example.paymentprocessor.service.TransactionService;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/transactions")
public class TransactionController {
    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @PostMapping("/process")
    public ResponseEntity<TransactionResponse> process(@Valid @RequestBody TransactionRequest request) {
        try {
            return ResponseEntity.ok(transactionService.process(request));
        } catch (DataIntegrityViolationException e) {
            // A concurrent request with the same transactionId won the unique-constraint race and has
            // committed. Its rollback has already undone this attempt, so retrying returns the stored result.
            return ResponseEntity.ok(transactionService.process(request));
        }
    }
}
