package com.example.paymentprocessor.exception;

public class InsufficientFundsException extends RuntimeException {
    public InsufficientFundsException() { super("Insufficient funds"); }
}
