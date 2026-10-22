package com.ticknova.identity;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SessionTokensTest {
    @Test void generatedTokensAreUnpredictableAndOnlyPepperedHashesAreStored() {
        var tokens = new SessionTokens("test-only-pepper-" + java.util.UUID.randomUUID());
        var one = tokens.generate();
        var two = tokens.generate();
        assertThat(one.token()).matches("[A-Za-z0-9_-]{43}").isNotEqualTo(two.token());
        assertThat(one.hash()).matches("[0-9a-f]{64}").isEqualTo(tokens.hash(one.token()));
        assertThat(one.hash()).doesNotContain(one.token());
        assertThat(new SessionTokens("other-test-pepper-" + java.util.UUID.randomUUID()).hash(one.token()))
            .isNotEqualTo(one.hash());
    }

    @Test void insufficientPepperIsRejectedAtStartup() {
        assertThatThrownBy(() -> new SessionTokens("short")).isInstanceOf(IllegalArgumentException.class);
    }
}
