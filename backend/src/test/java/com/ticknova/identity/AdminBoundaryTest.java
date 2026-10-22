package com.ticknova.identity;

import com.ticknova.catalog.CatalogService;
import com.ticknova.shared.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AdminBoundaryTest {
    private final Actor requester = new Actor(UUID.randomUUID(),"requester","Requester",Set.of("REQUESTER"),false);

    @Test void userAdministrationRejectsARequesterBeforeAccessingStorage() {
        var service = new UserAdminService(null,null,null);
        assertThatThrownBy(() -> service.list(requester,0,20))
            .isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.status()).isEqualTo(403));
        assertThatThrownBy(() -> service.setRoles(requester,UUID.randomUUID(),Set.of("ADMIN")))
            .isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.status()).isEqualTo(403));
    }

    @Test void adminRoleDoesNotGrantTicketQueueAuthority() {
        var service = new CatalogService(null,null);
        var admin = new Actor(UUID.randomUUID(),"admin","Admin",Set.of("ADMIN"),false);
        assertThat(service.canReadQueue(admin,UUID.randomUUID())).isFalse();
        assertThat(service.canProcessQueue(admin,UUID.randomUUID())).isFalse();
        assertThatThrownBy(() -> service.requireProcessQueue(admin,UUID.randomUUID()))
            .isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.status()).isEqualTo(403));
    }

    @Test void catalogMutationChecksAdminAtTheServiceBoundary() {
        var service = new CatalogService(null,null);
        assertThatThrownBy(() -> service.createQueue(requester,"SUPPORT","Support",true))
            .isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.status()).isEqualTo(403));
        assertThatThrownBy(() -> service.setMembership(requester,UUID.randomUUID(),UUID.randomUUID(),true))
            .isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.status()).isEqualTo(403));
        assertThatThrownBy(() -> service.createService(requester,"DEFAULT","Default",true))
            .isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.status()).isEqualTo(403));
    }
}
