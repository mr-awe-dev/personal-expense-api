package com.fastwork.expense.receipt;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Konfigurasi pembacaan struk (`app.receipt.*`). */
@Component
@ConfigurationProperties(prefix = "app.receipt")
public class ReceiptProperties {

    private String binary = "tesseract";
    private String languages = "ind+eng";
    private String psm = "6";
    private int timeoutSeconds = 25;
    private long maxImageBytes = 10L * 1024 * 1024;
    private boolean debugReturnOcrText = false;

    public String getBinary() {
        return binary;
    }

    public void setBinary(String binary) {
        this.binary = binary;
    }

    public String getLanguages() {
        return languages;
    }

    public void setLanguages(String languages) {
        this.languages = languages;
    }

    public String getPsm() {
        return psm;
    }

    public void setPsm(String psm) {
        this.psm = psm;
    }

    public boolean isDebugReturnOcrText() {
        return debugReturnOcrText;
    }

    public void setDebugReturnOcrText(boolean debugReturnOcrText) {
        this.debugReturnOcrText = debugReturnOcrText;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public long getMaxImageBytes() {
        return maxImageBytes;
    }

    public void setMaxImageBytes(long maxImageBytes) {
        this.maxImageBytes = maxImageBytes;
    }
}
