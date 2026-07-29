package com.parkio.parking.decision.calibration;

import com.parkio.parking.decision.assessment.AssessmentCategory;
import com.parkio.parking.decision.assessment.AssessmentCompleteness;
import com.parkio.parking.decision.assessment.AssessmentLevel;
import java.util.Objects;

/** One active category's level/completeness for calibration (no reserved absences). */
public final class AssessmentCategorySnapshot {

    private final AssessmentCategory category;
    private final AssessmentLevel level;
    private final AssessmentCompleteness completeness;

    public AssessmentCategorySnapshot(
            AssessmentCategory category, AssessmentLevel level, AssessmentCompleteness completeness) {
        this.category = Objects.requireNonNull(category, "category");
        this.level = Objects.requireNonNull(level, "level");
        this.completeness = Objects.requireNonNull(completeness, "completeness");
        if (category == AssessmentCategory.TRUST
                || category == AssessmentCategory.BEHAVIOR
                || category == AssessmentCategory.AVAILABILITY) {
            throw new IllegalArgumentException(
                    "reserved category must not appear in calibration snapshots: " + category);
        }
    }

    public AssessmentCategory category() {
        return category;
    }

    public AssessmentLevel level() {
        return level;
    }

    public AssessmentCompleteness completeness() {
        return completeness;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AssessmentCategorySnapshot that)) {
            return false;
        }
        return category == that.category && level == that.level && completeness == that.completeness;
    }

    @Override
    public int hashCode() {
        return Objects.hash(category, level, completeness);
    }
}
