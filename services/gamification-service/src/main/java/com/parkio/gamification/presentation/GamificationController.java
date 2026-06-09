package com.parkio.gamification.presentation;

import com.parkio.gamification.application.GamificationApplicationService;
import com.parkio.gamification.domain.UserLevelProgress;
import com.parkio.gamification.domain.exception.GamificationErrorCode;
import com.parkio.gamification.domain.exception.GamificationException;
import com.parkio.gamification.presentation.dto.AccessPolicyResponse;
import com.parkio.gamification.presentation.dto.LeaderboardEntryResponse;
import com.parkio.gamification.presentation.dto.LevelResponse;
import com.parkio.gamification.presentation.dto.LevelRuleResponse;
import com.parkio.gamification.presentation.dto.PointsResponse;
import com.parkio.gamification.presentation.dto.ProgressResponse;
import com.parkio.gamification.presentation.openapi.StandardApiResponses;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Gamification API. Translates HTTP into application calls and domain objects into
 * response DTOs — JPA entities never cross this boundary.
 *
 * <p>{@code /me/*} endpoints read the authenticated user id from the {@code X-User-Id}
 * header (gateway-injected) and fail closed if it is absent/invalid. {@code /levels}
 * and {@code /leaderboard} are not user-specific.
 */
@Tag(name = "Gamification", description = "Points, levels, progress and leaderboard")
@StandardApiResponses
@RestController
@RequestMapping("/api/v1/gamification")
public class GamificationController {

    private static final String USER_ID_HEADER = "X-User-Id";
    private static final int RECENT_TRANSACTIONS_LIMIT = 50;

    private final GamificationApplicationService gamificationService;

    public GamificationController(GamificationApplicationService gamificationService) {
        this.gamificationService = gamificationService;
    }

    @Operation(summary = "Get current user progress")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/me/progress")
    public ProgressResponse getMyProgress(@RequestHeader(value = USER_ID_HEADER, required = false) String userId) {
        return ProgressResponse.from(gamificationService.getProgress(requireUserId(userId)));
    }

    @Operation(summary = "Get current user points and recent transactions")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/me/points")
    public PointsResponse getMyPoints(@RequestHeader(value = USER_ID_HEADER, required = false) String userId) {
        UUID id = requireUserId(userId);
        UserLevelProgress progress = gamificationService.getProgress(id);
        return PointsResponse.of(progress,
                gamificationService.getRecentTransactions(id, RECENT_TRANSACTIONS_LIMIT));
    }

    @Operation(summary = "Get current user level")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/me/level")
    public LevelResponse getMyLevel(@RequestHeader(value = USER_ID_HEADER, required = false) String userId) {
        return LevelResponse.from(gamificationService.getLevelView(requireUserId(userId)));
    }

    @Operation(summary = "Get current user access policy")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/me/access-policy")
    public AccessPolicyResponse getMyAccessPolicy(
            @RequestHeader(value = USER_ID_HEADER, required = false) String userId) {
        return AccessPolicyResponse.from(gamificationService.getAccessPolicy(requireUserId(userId)));
    }

    @Operation(summary = "List level rules")
    @GetMapping("/levels")
    public List<LevelRuleResponse> getLevels() {
        return gamificationService.getLevels().stream().map(LevelRuleResponse::from).toList();
    }

    @Operation(summary = "Get leaderboard")
    @GetMapping("/leaderboard")
    public List<LeaderboardEntryResponse> getLeaderboard(
            @RequestParam(value = "limit", required = false) Integer limit) {
        List<UserLevelProgress> top = gamificationService.getLeaderboard(limit);
        return IntStream.range(0, top.size())
                .mapToObj(i -> LeaderboardEntryResponse.from(i + 1, top.get(i)))
                .toList();
    }

    /** Resolves the authenticated user id from the header; fails closed if absent/invalid. */
    private static UUID requireUserId(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            throw new GamificationException(GamificationErrorCode.MISSING_USER_ID, "Missing authenticated user id.");
        }
        try {
            return UUID.fromString(headerValue.trim());
        } catch (IllegalArgumentException ex) {
            throw new GamificationException(GamificationErrorCode.MISSING_USER_ID, "Invalid authenticated user id.");
        }
    }
}
