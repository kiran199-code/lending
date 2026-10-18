package com.bank.lending.iam.events;

import java.time.Instant;

public final class EventModels {
    private EventModels() {}

    public record EventRow(long id, String type, String subjectKey, String payload, Instant createdAt) {}

    public record SubscriptionRow(long id, String name, String url, String eventTypes, String secretEnc, boolean active, String approvalTypeCode, Instant createdAt) {
        public boolean wants(String eventType) {
            if (eventTypes == null || eventTypes.isBlank() || "*".equals(eventTypes.trim())) return true;
            for (String t : eventTypes.split(",")) if (t.trim().equals(eventType)) return true;
            return false;
        }
    }

    public record DeliveryRow(long id, long eventId, long subId, String status, int attempts, Instant nextAttemptAt, String lastError, Instant deliveredAt, Instant createdAt) {}

    /** A delivery with what is needed to send it. */
    public record DueDelivery(long deliveryId, int attempts, long eventId, String eventType, String payload, Instant eventCreatedAt,
                              long subId, String url, String secretEnc) {}
}
