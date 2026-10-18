package com.bank.lending.iam.events;

import com.bank.lending.iam.common.Json;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Writes events to the outbox in the caller's transaction. Delivery happens later, after commit (see WebhookDispatcher). */
@Service
public class EventService {
    public static final String USER_CREATED = "USER_CREATED";
    public static final String USER_STATUS_CHANGED = "USER_STATUS_CHANGED";
    public static final String ROLES_CHANGED = "ROLES_CHANGED";
    public static final String CLIENT_CHANGED = "CLIENT_CHANGED";
    public static final String APPROVAL_SUBMITTED = "APPROVAL_SUBMITTED";
    public static final String APPROVAL_APPROVED = "APPROVAL_APPROVED";
    public static final String APPROVAL_REJECTED = "APPROVAL_REJECTED";
    public static final String APPROVAL_CANCELLED = "APPROVAL_CANCELLED";
    public static final String APPROVAL_EXPIRED = "APPROVAL_EXPIRED";
    public static final String APPROVAL_CONSUMED = "APPROVAL_CONSUMED";
    public static final String CUSTOMER_LINK_CHANGED = "CUSTOMER_LINK_CHANGED";
    public static final String CONSENT_CHANGED = "CONSENT_CHANGED";

    private final EventRepository events;

    public EventService(EventRepository events) {
        this.events = events;
    }

    /** @param subjectKey events with the same key are delivered in order to each subscriber */
    @Transactional
    public long publish(String type, String subjectKey, Object... payloadKv) {
        return events.insertEvent(type, subjectKey, Json.obj(payloadKv));
    }
}
