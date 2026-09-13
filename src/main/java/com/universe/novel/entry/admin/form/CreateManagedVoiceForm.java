package com.universe.novel.entry.admin.form;

public class CreateManagedVoiceForm {

    private String voiceKey;

    private String displayName;

    private String providerVoiceId;

    private boolean defaultVoice = false;

    public String getVoiceKey() {
        return voiceKey;
    }

    public void setVoiceKey(String voiceKey) {
        this.voiceKey = voiceKey;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getProviderVoiceId() {
        return providerVoiceId;
    }

    public void setProviderVoiceId(String providerVoiceId) {
        this.providerVoiceId = providerVoiceId;
    }

    public boolean isDefaultVoice() {
        return defaultVoice;
    }

    public void setDefaultVoice(boolean defaultVoice) {
        this.defaultVoice = defaultVoice;
    }
}
