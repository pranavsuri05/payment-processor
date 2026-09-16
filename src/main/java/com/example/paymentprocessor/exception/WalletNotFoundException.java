package com.example.paymentprocessor.exception;

public class WalletNotFoundException extends RuntimeException {
    public WalletNotFoundException() { super("Wallet not found"); }
}
