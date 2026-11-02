package cn.diaojiahong.myyoutube.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class PlaybackTokensTest {
    private static final String SECRET = "test-secret-at-least-thirty-two-characters";
    private final Clock now = Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC);
    private final PlaybackTokens tokens = new PlaybackTokens(SECRET, 21600, now);

    @Test
    void ticketIsReusableButExpiresAtItsDeadline() {
        PlaybackTokens.Grant grant = tokens.issue(9, 123, 12000, PlaybackTokens.Scope.VIEW);
        assertEquals(grant, tokens.verify(grant.token()));
        assertEquals(grant, tokens.verify(grant.token()));
        assertEquals(now.millis() + Duration.ofHours(6).toMillis(), grant.expiresAt());
        PlaybackTokens expired = new PlaybackTokens(SECRET, 21600, Clock.offset(now, Duration.ofHours(6)));
        assertNull(expired.verify(grant.token()));
    }

    @Test
    void smallClockDifferencesBetweenVideoAndGatewayDoNotRejectNewTickets() {
        PlaybackTokens issuer = new PlaybackTokens(SECRET, 21600, Clock.offset(now, Duration.ofSeconds(5)));
        assertNotNull(tokens.verify(issuer.issue(9, 123, 1000, PlaybackTokens.Scope.VIEW).token()));
        PlaybackTokens farFuture = new PlaybackTokens(SECRET, 21600, Clock.offset(now, Duration.ofMinutes(1)));
        assertNull(tokens.verify(farFuture.issue(9, 123, 1000, PlaybackTokens.Scope.VIEW).token()));
    }

    @Test
    void longVideosReceiveTheirDurationPlusPauseAllowance() {
        long duration = Duration.ofHours(8).toMillis();
        assertEquals(now.millis() + duration + Duration.ofHours(1).toMillis(),
                tokens.issue(9, 123, duration, PlaybackTokens.Scope.VIEW).expiresAt());
    }

    @Test
    void ticketsAndAccessTokensCannotBeSubstitutedForEachOther() {
        JwtUtil.init(SECRET);
        assertNull(tokens.verify(JwtUtil.sign(9)));
        assertNull(JwtUtil.verify(tokens.issue(9, 123, 1000, PlaybackTokens.Scope.VIEW).token()));
        assertNull(new PlaybackTokens("another-signing-secret", 21600, now)
                .verify(tokens.issue(9, 123, 1000, PlaybackTokens.Scope.VIEW).token()));
    }

    @Test
    void malformedAndModifiedTicketsAreRejected() {
        assertNull(tokens.verify(null));
        assertNull(tokens.verify(""));
        assertNull(tokens.verify("invalid"));
        String token = tokens.issue(9, 123, 1000, PlaybackTokens.Scope.VIEW).token();
        int signature = token.lastIndexOf('.') + 1;
        String changed = token.substring(0, signature) + (token.charAt(signature) == 'a' ? 'b' : 'a')
                + token.substring(signature + 1);
        assertNull(tokens.verify(changed));
    }
}
