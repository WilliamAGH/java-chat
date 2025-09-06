package com.williamcallahan.javachat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.ports")
public class TestPortConfig {
    private boolean killOnConflict = false;
    private String range = "18085-18090";

    public boolean isKillOnConflict() {
        return killOnConflict;
    }

    public void setKillOnConflict(boolean killOnConflict) {
        this.killOnConflict = killOnConflict;
    }

    public String getRange() {
        return range;
    }

    public void setRange(String range) {
        this.range = range;
    }
}
