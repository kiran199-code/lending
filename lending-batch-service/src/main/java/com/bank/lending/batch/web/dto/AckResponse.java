package com.bank.lending.batch.web.dto;

public record AckResponse(String status, String message) {
    public static AckResponse of(String message) {
        return new AckResponse("OK", message);
    }
}
