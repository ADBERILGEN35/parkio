package com.parkio.gamification.infrastructure.persistence.entity;

import com.parkio.gamification.domain.PointSourceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** JPA mapping for {@code penalty_rules} (seeded reference data). */
@Entity
@Table(name = "penalty_rules")
public class PenaltyRuleEntity {

    @Id
    @Column(name = "rule_key", nullable = false, updatable = false)
    private String ruleKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false)
    private PointSourceType sourceType;

    @Column(name = "points", nullable = false)
    private int points;

    @Column(name = "description")
    private String description;

    protected PenaltyRuleEntity() {
        // for JPA
    }

    public String getRuleKey() {
        return ruleKey;
    }

    public PointSourceType getSourceType() {
        return sourceType;
    }

    public int getPoints() {
        return points;
    }

    public String getDescription() {
        return description;
    }
}
