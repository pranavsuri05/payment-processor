package com.example.paymentprocessor.controller;

import com.example.paymentprocessor.entity.Wallet;
import com.example.paymentprocessor.repository.WalletRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/wallets")
public class WalletController {
    private final WalletRepository walletRepository;

    public WalletController(WalletRepository walletRepository) {
        this.walletRepository = walletRepository;
    }

    @PostMapping
    public ResponseEntity<Wallet> create(@RequestParam UUID userId,
                                         @RequestParam BigDecimal balance) {
        return ResponseEntity.ok(walletRepository.save(new Wallet(userId, balance)));
    }
}
