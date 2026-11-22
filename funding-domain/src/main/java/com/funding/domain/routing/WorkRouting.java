package com.funding.domain.routing;

import com.funding.domain.Check;
import com.funding.domain.DocumentType;
import com.funding.domain.Outcome;
import com.funding.domain.Role;
import com.funding.domain.StipCodes;
import com.funding.domain.status.VersionStatus;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Who may work a deal (spec 002 §3 "Kind of work" and "Routing", D-15, D-17). Pure and table-driven;
 * derived, never stored. {@link #kinds} reads the deal's pending stips, current results, holds and
 * payment; {@link #route} turns the kinds into a role: one kind → its specialist, two or more → the
 * income specialist, none → nobody but the supervisor (who may work any deal anyway).
 */
public final class WorkRouting {
    private WorkRouting() {}

    /** The payment's state, as the overview reports it (plan §5). */
    public enum PaymentState { NOT_STARTED, IN_FLIGHT, PAID, FAILED, UNKNOWN }

    /**
     * A pending stip. {@code check} is the check that raised it, or the one a manual stip names
     * (null when it names none). {@code flaggedFields} are a field review's low-confidence fields as
     * {@code DOC.key} (e.g. {@code RISC.cash_price}); empty for other stips.
     */
    public record PendingStip(String code, Check check, List<String> flaggedFields) {
        public PendingStip {
            flaggedFields = flaggedFields == null ? List.of() : List.copyOf(flaggedFields);
        }

        public static PendingStip of(String code, Check check) {
            return new PendingStip(code, check, List.of());
        }
    }

    /** A check's current result. */
    public record CurrentResult(Check check, Outcome outcome) {}

    /** What routing reads about a deal (its latest version). */
    public record Deal(VersionStatus status, List<PendingStip> pendingStips, List<CurrentResult> results,
                       boolean riscIncomplete, PaymentState payment) {
        public Deal {
            pendingStips = pendingStips == null ? List.of() : List.copyOf(pendingStips);
            results = results == null ? List.of() : List.copyOf(results);
            payment = payment == null ? PaymentState.NOT_STARTED : payment;
        }
    }

    /** The stip codes the table maps (spec §3); anything else falls to its check, or GENERAL. */
    static final Map<String, WorkKind> BY_CODE = Map.of(
            StipCodes.GAP_FORM_MISMATCH, WorkKind.GAP,
            StipCodes.LENDER_MISMATCH, WorkKind.GAP,
            StipCodes.INCOME_VARIANCE_EXCEEDED, WorkKind.INCOME,
            StipCodes.PROOF_OF_INCOME, WorkKind.INCOME,
            StipCodes.TILA_MATH_DISCREPANCY, WorkKind.RISC,
            StipCodes.PRICE_MISMATCH, WorkKind.DOCUMENTS,
            StipCodes.VIN_MISMATCH, WorkKind.DOCUMENTS);

    static final Map<Check, WorkKind> BY_CHECK = Map.of(
            Check.GAP, WorkKind.GAP, Check.INCOME, WorkKind.INCOME, Check.TILA, WorkKind.RISC,
            Check.PRICE, WorkKind.DOCUMENTS, Check.VIN, WorkKind.DOCUMENTS);

    static final Map<DocumentType, WorkKind> BY_DOCUMENT = Map.of(
            DocumentType.RISC, WorkKind.RISC,
            DocumentType.BUYERS_ORDER, WorkKind.DOCUMENTS,
            DocumentType.GAP_AGREEMENT, WorkKind.GAP);

    private static final Set<VersionStatus> NO_HUMAN_NEEDED =
            EnumSet.of(VersionStatus.DRAFT, VersionStatus.FUNDED, VersionStatus.REJECTED, VersionStatus.EXPIRED);
    private static final Set<VersionStatus> UNDER_REVIEW = EnumSet.of(VersionStatus.PENDING_REVIEW, VersionStatus.STIPULATED);

    /** The deal's kinds of work. A finished or unsubmitted deal has none. */
    public static Set<WorkKind> kinds(Deal deal) {
        Set<WorkKind> kinds = EnumSet.noneOf(WorkKind.class);
        if (NO_HUMAN_NEEDED.contains(deal.status())) {
            return kinds;
        }
        for (PendingStip stip : deal.pendingStips()) {
            kinds.addAll(kindsOf(stip));
        }
        if (UNDER_REVIEW.contains(deal.status())) {
            // A check that couldn't finish, or income needing review, is work even with no stip.
            // Once a deal moves on (e.g. approved over an ERROR), old results route nothing.
            for (CurrentResult r : deal.results()) {
                if (r.outcome() == Outcome.ERROR || (r.check() == Check.INCOME && r.outcome() == Outcome.NEEDS_REVIEW)) {
                    kinds.add(BY_CHECK.get(r.check()));
                }
            }
            if (deal.riscIncomplete()) {
                kinds.add(WorkKind.RISC);
            }
        }
        if (deal.status() == VersionStatus.APPROVED
                && (deal.payment() == PaymentState.FAILED || deal.payment() == PaymentState.UNKNOWN)) {
            kinds.add(WorkKind.PAYMENT);
        }
        return kinds;
    }

    /**
     * One stip's kinds. Mapped codes by the table; a field review by the document its fields are on
     * (more than one document → more than one kind); any other code by the check it names (a manual
     * stip), or GENERAL when it names none.
     */
    public static Set<WorkKind> kindsOf(PendingStip stip) {
        WorkKind mapped = BY_CODE.get(stip.code());
        if (mapped != null) {
            return EnumSet.of(mapped);
        }
        if (StipCodes.EXTRACTION_REVIEW_REQUIRED.equals(stip.code())) {
            Set<WorkKind> byDocument = EnumSet.noneOf(WorkKind.class);
            for (String field : stip.flaggedFields()) {
                documentOf(field).map(BY_DOCUMENT::get).ifPresent(byDocument::add);
            }
            if (!byDocument.isEmpty()) {
                return byDocument;
            }
        }
        return EnumSet.of(stip.check() != null ? BY_CHECK.get(stip.check()) : WorkKind.GENERAL);
    }

    /** Who the deal is routed to; empty when nothing needs a specialist (only the supervisor acts). */
    public static Optional<Role> route(Set<WorkKind> kinds) {
        if (kinds.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(kinds.size() == 1 ? kinds.iterator().next().specialist() : Role.INCOME_SPECIALIST);
    }

    public static Optional<Role> route(Deal deal) {
        return route(kinds(deal));
    }

    private static Optional<DocumentType> documentOf(String field) {
        int dot = field.indexOf('.');
        if (dot <= 0) {
            return Optional.empty();
        }
        try {
            return Optional.of(DocumentType.valueOf(field.substring(0, dot)));
        } catch (IllegalArgumentException notADocument) {
            return Optional.empty();
        }
    }
}
