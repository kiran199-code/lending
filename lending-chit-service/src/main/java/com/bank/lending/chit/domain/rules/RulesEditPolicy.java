package com.bank.lending.chit.domain.rules;

import com.bank.lending.chit.common.ChitException;
import com.bank.lending.chit.domain.GroupStatus;

/**
 * Rules can be changed only while the group is a DRAFT or open for enrolment. From FULL onwards they are frozen
 * (activation freezes the snapshot; changing rules after members have enrolled in a full group would be unfair).
 */
public final class RulesEditPolicy {

    private RulesEditPolicy() {
    }

    public static boolean isEditable(GroupStatus status) {
        return status == GroupStatus.DRAFT || status == GroupStatus.OPEN_FOR_ENROLMENT;
    }

    public static void assertEditable(GroupStatus status) {
        if (!isEditable(status)) {
            throw new ChitException("RULES_FROZEN", "Rules cannot be changed while the group is " + status);
        }
    }
}
