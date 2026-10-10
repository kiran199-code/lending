package com.bank.lending.statement;

/** A rendered statement file and the name it should be downloaded under. */
public record GeneratedStatement(String fileName, byte[] content) {
}
