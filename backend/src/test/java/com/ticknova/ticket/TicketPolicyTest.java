package com.ticknova.ticket;

import com.ticknova.shared.Actor;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class TicketPolicyTest {
    final UUID owner = UUID.randomUUID();
    final Actor requester = new Actor(owner,"requester","请求者",Set.of("REQUESTER"),false);
    final Actor agent = new Actor(UUID.randomUUID(),"agent","工程师",Set.of("AGENT"),false);
    final Actor admin = new Actor(UUID.randomUUID(),"admin","管理员",Set.of("ADMIN"),false);
    final Actor combined = new Actor(UUID.randomUUID(),"both","双角色",Set.of("ADMIN","AGENT"),false);

    @Test void everyDocumentedAgentEdgeAndEveryOtherPair() {
        Set<String> edges = Set.of("NEW:TRIAGED","TRIAGED:IN_PROGRESS","IN_PROGRESS:WAITING_CUSTOMER",
            "IN_PROGRESS:WAITING_THIRD_PARTY","WAITING_CUSTOMER:IN_PROGRESS","WAITING_THIRD_PARTY:IN_PROGRESS",
            "IN_PROGRESS:RESOLVED","RESOLVED:CLOSED");
        for (String from: TicketPolicy.STATES) for (String to: TicketPolicy.STATES)
            assertThat(TicketPolicy.canTransition(agent,owner,true,from,to)).as(from+"→"+to).isEqualTo(edges.contains(from+":"+to));
    }
    @Test void requesterCanConfirmOrReopenOnlyTheirOwnResolvedTicket() {
        for(String from: TicketPolicy.STATES) for(String to: TicketPolicy.STATES) {
            boolean expected=from.equals("RESOLVED") && List.of("CLOSED","IN_PROGRESS").contains(to);
            assertThat(TicketPolicy.canTransition(requester,owner,false,from,to)).isEqualTo(expected);
            assertThat(TicketPolicy.canTransition(requester,UUID.randomUUID(),false,from,to)).isFalse();
        }
    }
    @Test void adminDoesNotInheritQueuePermissionOrAgentRole() {
        assertThat(TicketPolicy.canTransition(admin,owner,true,"CLOSED","IN_PROGRESS")).isFalse();
        assertThat(TicketPolicy.canTransition(combined,owner,false,"CLOSED","IN_PROGRESS")).isFalse();
        assertThat(TicketPolicy.canTransition(combined,owner,true,"CLOSED","IN_PROGRESS")).isTrue();
        assertThat(TicketPolicy.canTransition(agent,owner,false,"NEW","TRIAGED")).isFalse();
    }
}
