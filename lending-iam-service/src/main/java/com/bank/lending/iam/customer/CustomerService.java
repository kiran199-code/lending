package com.bank.lending.iam.customer;

import com.bank.lending.iam.access.AccessService;
import com.bank.lending.iam.audit.AuditService;
import com.bank.lending.iam.common.IamException;
import com.bank.lending.iam.common.Validate;
import com.bank.lending.iam.customer.CustomerDtos.*;
import com.bank.lending.iam.customer.CustomerModels.LinkRow;
import com.bank.lending.iam.events.EventService;
import com.bank.lending.iam.user.UserRepository;
import com.bank.lending.iam.user.UserRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Links between a customer login and customers or accounts held in the lending platform. IAM stores only the
 * references. An OWNER link is effective at once; the other relationships need a second person.
 */
@Service
public class CustomerService {
    private final CustomerRepository customers;
    private final UserRepository users;
    private final AccessService access;
    private final AuditService audit;
    private final EventService events;
    private final Clock clock;

    public CustomerService(CustomerRepository customers, UserRepository users, AccessService access, AuditService audit, EventService events, Clock clock) {
        this.customers = customers;
        this.users = users;
        this.access = access;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    private LinkView view(LinkRow l) {
        String username = users.findById(l.userId()).map(UserRow::username).orElse(String.valueOf(l.userId()));
        return new LinkView(l.id(), l.userId(), username, l.customerId(), l.linkType(), l.accountRef(), l.relationship(), l.status(), l.validFrom(), l.validTo(),
                l.createdBy(), l.createdAt(), l.approvedBy(), l.revokedBy(), l.reason());
    }

    public List<LinkView> linksOf(long userId) {
        users.findById(userId).orElseThrow(() -> IamException.notFound("User", String.valueOf(userId)));
        return customers.linksOf(userId).stream().map(this::view).toList();
    }

    public List<LinkView> pending() {
        return customers.pending().stream().map(this::view).toList();
    }

    @Transactional
    public LinkView create(long userId, CreateLinkRequest r, String actor) {
        UserRow u = users.findByIdForUpdate(userId).orElseThrow(() -> IamException.notFound("User", String.valueOf(userId)));
        if (!u.customer()) throw IamException.unprocessable("NOT_A_CUSTOMER", "Links are for customer users");
        if ("OFFBOARDED".equals(u.status()) || "SUSPENDED".equals(u.status())) throw IamException.conflict("INVALID_STATE", "A " + u.status() + " user cannot be linked");
        String type = Validate.oneOf(r.linkType(), "linkType", "CUSTOMER", "ACCOUNT");
        String rel = Validate.oneOf(r.relationship(), "relationship", "OWNER", "CO_BORROWER", "GUARANTOR", "DELEGATE");
        if (!"OWNER".equals(rel) && !"ACCOUNT".equals(type)) {
            throw IamException.unprocessable("LINK_TYPE_NOT_ALLOWED", rel + " links are for one account (linkType ACCOUNT)");
        }
        if ("DELEGATE".equals(rel) && r.validTo() == null) throw IamException.unprocessable("END_DATE_REQUIRED", "A delegate link needs an end date");
        String accountRef = null;
        String customerId;
        if ("ACCOUNT".equals(type)) {
            accountRef = Validate.ref(r.accountRef(), "accountRef");
            customerId = r.customerId() == null || r.customerId().isBlank() ? u.customerId() : Validate.ref(r.customerId(), "customerId");
        } else {
            if (r.accountRef() != null && !r.accountRef().isBlank()) throw IamException.badRequest("VALIDATION_FAILED", "A CUSTOMER link has no accountRef");
            customerId = u.customerId();   // all accounts of the user's own customer
            if (r.customerId() != null && !r.customerId().isBlank() && !r.customerId().trim().equals(customerId)) {
                throw IamException.unprocessable("CUSTOMER_MISMATCH", "A CUSTOMER link is for the user's own customerId");
            }
        }
        LocalDate from = r.validFrom() == null ? LocalDate.now(clock.withZone(ZoneOffset.UTC)) : r.validFrom();
        if (r.validTo() != null && r.validTo().isBefore(from)) throw IamException.badRequest("VALIDATION_FAILED", "validTo is before validFrom");
        for (LinkRow existing : customers.linksOf(userId)) {
            if (("ACTIVE".equals(existing.status()) || "PENDING_APPROVAL".equals(existing.status())) && existing.linkType().equals(type)
                    && java.util.Objects.equals(existing.accountRef(), accountRef) && existing.customerId().equals(customerId) && existing.relationship().equals(rel)) {
                throw IamException.conflict("LINK_EXISTS", "The same link already exists");
            }
        }
        String status = "OWNER".equals(rel) ? "ACTIVE" : "PENDING_APPROVAL";
        long id = customers.insertLink(userId, customerId, type, accountRef, rel, status, from, r.validTo(), actor);
        audit.record("CUSTOMER_LINK_CREATED", "USER", u.username(), "SUCCESS", "linkId", id, "linkType", type, "relationship", rel, "accountRef", accountRef, "status", status);
        events.publish(EventService.CUSTOMER_LINK_CHANGED, "user:" + u.username(), "username", u.username(), "linkId", id, "status", status, "relationship", rel, "accountRef", accountRef);
        access.invalidateAll();
        return view(customers.link(id).orElseThrow());
    }

    @Transactional
    public LinkView approve(long linkId, String actor) {
        LinkRow l = customers.linkForUpdate(linkId).orElseThrow(() -> IamException.notFound("Link", String.valueOf(linkId)));
        if (l.createdBy().equalsIgnoreCase(actor)) throw IamException.forbidden("MAKER_CANNOT_APPROVE", "A different administrator must approve this link");
        if (!customers.approve(linkId, actor)) throw IamException.conflict("INVALID_STATE", "Only a link waiting for approval can be approved");
        UserRow u = users.findById(l.userId()).orElseThrow();
        audit.record("CUSTOMER_LINK_APPROVED", "USER", u.username(), "SUCCESS", "linkId", linkId, "createdBy", l.createdBy());
        events.publish(EventService.CUSTOMER_LINK_CHANGED, "user:" + u.username(), "username", u.username(), "linkId", linkId, "status", "ACTIVE");
        access.invalidateAll();
        return view(customers.link(linkId).orElseThrow());
    }

    @Transactional
    public LinkView reject(long linkId, String reason, String actor) {
        LinkRow l = customers.linkForUpdate(linkId).orElseThrow(() -> IamException.notFound("Link", String.valueOf(linkId)));
        if (l.createdBy().equalsIgnoreCase(actor)) throw IamException.forbidden("MAKER_CANNOT_APPROVE", "A different administrator must decide this link");
        if (!customers.reject(linkId, actor, Validate.text(reason, "reason", 300, true))) throw IamException.conflict("INVALID_STATE", "Only a link waiting for approval can be rejected");
        UserRow u = users.findById(l.userId()).orElseThrow();
        audit.record("CUSTOMER_LINK_REJECTED", "USER", u.username(), "SUCCESS", "linkId", linkId, "reason", reason);
        events.publish(EventService.CUSTOMER_LINK_CHANGED, "user:" + u.username(), "username", u.username(), "linkId", linkId, "status", "REJECTED");
        return view(customers.link(linkId).orElseThrow());
    }

    @Transactional
    public LinkView revoke(long linkId, String reason, String actor) {
        LinkRow l = customers.linkForUpdate(linkId).orElseThrow(() -> IamException.notFound("Link", String.valueOf(linkId)));
        if (!customers.revoke(linkId, actor, Validate.text(reason, "reason", 300, true))) throw IamException.conflict("INVALID_STATE", "The link is already " + l.status());
        UserRow u = users.findById(l.userId()).orElseThrow();
        audit.record("CUSTOMER_LINK_REVOKED", "USER", u.username(), "SUCCESS", "linkId", linkId, "reason", reason);
        events.publish(EventService.CUSTOMER_LINK_CHANGED, "user:" + u.username(), "username", u.username(), "linkId", linkId, "status", "REVOKED");
        access.invalidateAll();
        return view(customers.link(linkId).orElseThrow());
    }
}
