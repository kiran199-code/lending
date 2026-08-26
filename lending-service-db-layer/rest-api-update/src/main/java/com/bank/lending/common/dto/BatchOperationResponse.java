package com.bank.lending.common.dto;

/** Simple acknowledgement body for endpoints that trigger a synchronous batch job. */
public record BatchOperationResponse(String status, String message) {
    public static BatchOperationResponse completed(String message) {
        return new BatchOperationResponse("COMPLETED", message);
    }
}
