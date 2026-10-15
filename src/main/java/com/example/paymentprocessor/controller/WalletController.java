package com.example.paymentprocessor.controller;

import com.example.paymentprocessor.entity.Wallet;
import com.example.paymentprocessor.repository.WalletRepository;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/wallets")
@Validated
public class WalletController {
    private final WalletRepository walletRepository;

    public WalletController(WalletRepository walletRepository) {
        this.walletRepository = walletRepository;
    }

    @PostMapping
    public ResponseEntity<Wallet> create(@RequestParam UUID userId,
                                         @RequestParam @PositiveOrZero @Digits(integer = 17, fraction = 2) BigDecimal balance) {
        if (walletRepository.existsById(userId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Wallet already exists");
        }
        return ResponseEntity.ok(walletRepository.save(new Wallet(userId, balance)));
    }
}
