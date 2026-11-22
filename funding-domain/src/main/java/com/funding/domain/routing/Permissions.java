package com.funding.domain.routing;

import com.funding.domain.Role;
import com.funding.domain.routing.WorkRouting.Deal;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * What a user may do to a deal (spec 002 AS-11.2, built on {@link WorkRouting}). Pure and
 * table-driven, so the funder API (which enforces it, FR-11) and the overview's allowed actions
 * (FR-05b) can't disagree. Status rules (spec 001 §6) still apply on top, in the API's own guards.
 *
 * <ul>
 *   <li>Reading is always allowed (AS-11.1).</li>
 *   <li>The supervisor may do everything; reassigning and assignment suggestions are theirs alone.</li>
 *   <li>A specialist may take a deal routed to them, and do every other write on a deal that is
 *       routed to them <em>and</em> assigned to them.</li>
 * </ul>
 */
public final class Permissions {
    private Permissions() {}

    public enum Action {
        VIEW, TAKE, RESOLVE_STIP, ADD_STIP, RERUN, APPROVE, REJECT, RETRY_PAYMENT, REASSIGN, SUGGEST_ASSIGNMENTS
    }

    /** Why a write is refused (403 problem+json, AS-11.3). */
    public static final String ROLE_NOT_ALLOWED = "ROLE_NOT_ALLOWED";
    public static final String NOT_YOUR_SPECIALTY = "NOT_YOUR_SPECIALTY";
    public static final String NOT_ASSIGNED_TO_YOU = "NOT_ASSIGNED_TO_YOU";

    /** Allowed, or refused with a reason. */
    public record Decision(boolean allowed, String reason) {
        static final Decision ALLOWED = new Decision(true, null);

        static Decision refused(String reason) {
            return new Decision(false, reason);
        }
    }

    private static final Set<Action> SUPERVISOR_ONLY = EnumSet.of(Action.REASSIGN, Action.SUGGEST_ASSIGNMENTS);

    /**
     * May {@code user} (with {@code role}) do {@code action} to {@code deal}, which is assigned to
     * {@code assignee} (null when nobody has it)?
     */
    public static Decision check(Role role, String user, Action action, Deal deal, String assignee) {
        Objects.requireNonNull(role, "role");
        if (action == Action.VIEW || role == Role.SUPERVISOR) {
            return Decision.ALLOWED;
        }
        if (SUPERVISOR_ONLY.contains(action) || role == Role.FUNDER) {     // FUNDER: the pre-D2 generalist
            return Decision.refused(ROLE_NOT_ALLOWED);
        }
        Optional<Role> routed = WorkRouting.route(deal);
        if (routed.isEmpty() || routed.get() != role) {
            return Decision.refused(NOT_YOUR_SPECIALTY);
        }
        if (action == Action.TAKE) {
            return Decision.ALLOWED;                                          // ALREADY_ASSIGNED stays the API's guard
        }
        return user != null && user.equals(assignee) ? Decision.ALLOWED : Decision.refused(NOT_ASSIGNED_TO_YOU);
    }

    /** Every action's decision for this user and deal: the overview's allowed actions (FR-05b). */
    public static Map<Action, Decision> all(Role role, String user, Deal deal, String assignee) {
        Map<Action, Decision> out = new EnumMap<>(Action.class);
        for (Action a : Action.values()) {
            out.put(a, check(role, user, a, deal, assignee));
        }
        return out;
    }

    /**
     * AS-11.5: the deal's assignee can no longer work it (its routing changed), so it belongs on the
     * supervisor's "needs reassignment" list. A supervisor assignee, or no assignee, never needs it.
     */
    public static boolean needsReassignment(Deal deal, Role assigneeRole) {
        if (assigneeRole == null || assigneeRole == Role.SUPERVISOR) {
            return false;
        }
        Optional<Role> routed = WorkRouting.route(deal);
        return routed.isPresent() && routed.get() != assigneeRole;
    }
}
