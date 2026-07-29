package com.parkio.parking.application.port;

import com.parkio.parking.application.reward.ValidatedOutcomeForReward;
import java.util.List;

public interface ValidatedOutcomeForRewardReadPort {

    List<ValidatedOutcomeForReward> claimPendingReporterOutcomes(int limit);
}
