package com.ticknova.ticket;

import com.ticknova.shared.Actor;
import java.util.List;
import java.util.UUID;
import java.util.Set;

public final class TicketPolicy {
    public static final List<String> STATES=List.of("NEW","TRIAGED","IN_PROGRESS","WAITING_CUSTOMER","WAITING_THIRD_PARTY","RESOLVED","CLOSED");
    private TicketPolicy() {}
    private static final Set<String> AGENT_EDGES=Set.of("NEW:TRIAGED","TRIAGED:IN_PROGRESS",
        "IN_PROGRESS:WAITING_CUSTOMER","IN_PROGRESS:WAITING_THIRD_PARTY","WAITING_CUSTOMER:IN_PROGRESS",
        "WAITING_THIRD_PARTY:IN_PROGRESS","IN_PROGRESS:RESOLVED","RESOLVED:CLOSED");
    public static boolean canTransition(Actor actor, UUID requesterId, boolean canProcess, String from, String to) {
        if(from.equals("RESOLVED") && actor.id().equals(requesterId)
                && (actor.hasRole("REQUESTER") || actor.hasRole("AGENT")) && Set.of("CLOSED","IN_PROGRESS").contains(to)) return true;
        if(!actor.hasRole("AGENT") || !canProcess) return false;
        if(from.equals("CLOSED") && to.equals("IN_PROGRESS")) return actor.hasRole("ADMIN");
        return AGENT_EDGES.contains(from+":"+to);
    }
}
