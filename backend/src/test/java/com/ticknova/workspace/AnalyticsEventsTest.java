package com.ticknova.workspace;

import com.ticknova.identity.AuthService;
import com.ticknova.shared.Actor;
import com.ticknova.shared.ApiException;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnalyticsEventsTest {
    @Test void subscriptionRequiresCurrentAdminAndAvailableListener() {
        AuthService auth=mock(AuthService.class);
        DataSource data=mock(DataSource.class);
        var events=new AnalyticsEvents(data,auth);
        var requester=new Actor(UUID.randomUUID(),"requester","Requester",Set.of("REQUESTER"),false);
        var admin=new Actor(UUID.randomUUID(),"admin","Admin",Set.of("ADMIN"),false);
        when(auth.authenticate("requester")).thenReturn(requester);
        when(auth.authenticate("admin")).thenReturn(admin);
        assertThatThrownBy(() -> events.subscribe("requester")).isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.status()).isEqualTo(403));
        assertThatThrownBy(() -> events.subscribe("admin")).isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.status()).isEqualTo(503));
        events.close();
    }
}
