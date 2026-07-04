package com.parkio.gamification.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** JPA mapping for {@code trust_rules} (seeded reference data). */
@Entity
@Table(name = "trust_rules")
public class TrustRuleEntity {

    @Id
    @Column(name = "rule_key", nullable = false, updatable = false)
    private String ruleKey;

    @Column(name = "delta", nullable = false)
    private int delta;

    @Column(name = "description")
    private String description;

    protected TrustRuleEntity() {
        // for JPA
    }

    public String getRuleKey() {
        return ruleKey;
    }

    public int getDelta() {
        return delta;
    }

    public String getDescription() {
        return description;
    }
}
