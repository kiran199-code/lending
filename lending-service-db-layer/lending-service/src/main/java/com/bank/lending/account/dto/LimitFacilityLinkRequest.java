package com.bank.lending.account.dto;

import jakarta.validation.constraints.Size;

/** Sets the limit facility of an account; a blank or missing reference clears it. */
public record LimitFacilityLinkRequest(@Size(max = 64) String facilityRef) {}
