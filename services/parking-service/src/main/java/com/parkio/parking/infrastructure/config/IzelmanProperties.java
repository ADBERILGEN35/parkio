package com.parkio.parking.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "parkio.municipal.izelman")
public class IzelmanProperties {
    private boolean enabled;
    private boolean facilityImportEnabled;
    private boolean roadsideImportEnabled;
    private boolean tariffImportEnabled;
    private boolean facilityPublicationEnabled;
    private boolean roadsidePublicationEnabled;
    private boolean tariffPublicationEnabled;
    private boolean schedulerEnabled;
    private boolean candidateGenerationEnabled;
    private boolean autoMatchEnabled;
    private String allowedInputDir = "";
    private long maxInputBytes = 5L * 1024 * 1024;
    private long agingAfterDays = 180;
    private long historicalAfterDays = 730;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isFacilityImportEnabled() { return facilityImportEnabled; }
    public void setFacilityImportEnabled(boolean value) { facilityImportEnabled = value; }
    public boolean isRoadsideImportEnabled() { return roadsideImportEnabled; }
    public void setRoadsideImportEnabled(boolean value) { roadsideImportEnabled = value; }
    public boolean isTariffImportEnabled() { return tariffImportEnabled; }
    public void setTariffImportEnabled(boolean value) { tariffImportEnabled = value; }
    public boolean isFacilityPublicationEnabled() { return facilityPublicationEnabled; }
    public void setFacilityPublicationEnabled(boolean value) { facilityPublicationEnabled = value; }
    public boolean isRoadsidePublicationEnabled() { return roadsidePublicationEnabled; }
    public void setRoadsidePublicationEnabled(boolean value) { roadsidePublicationEnabled = value; }
    public boolean isTariffPublicationEnabled() { return tariffPublicationEnabled; }
    public void setTariffPublicationEnabled(boolean value) { tariffPublicationEnabled = value; }
    public boolean isSchedulerEnabled() { return schedulerEnabled; }
    public void setSchedulerEnabled(boolean value) { schedulerEnabled = value; }
    public boolean isCandidateGenerationEnabled() { return candidateGenerationEnabled; }
    public void setCandidateGenerationEnabled(boolean value) { candidateGenerationEnabled = value; }
    public boolean isAutoMatchEnabled() { return autoMatchEnabled; }
    public void setAutoMatchEnabled(boolean value) {
        if (value) throw new IllegalArgumentException("İZELMAN auto-match is not supported");
        autoMatchEnabled = false;
    }
    public String getAllowedInputDir() { return allowedInputDir; }
    public void setAllowedInputDir(String value) { allowedInputDir = value; }
    public long getMaxInputBytes() { return maxInputBytes; }
    public void setMaxInputBytes(long value) { maxInputBytes = value; }
    public long getAgingAfterDays() { return agingAfterDays; }
    public void setAgingAfterDays(long value) { agingAfterDays = value; }
    public long getHistoricalAfterDays() { return historicalAfterDays; }
    public void setHistoricalAfterDays(long value) { historicalAfterDays = value; }
}
