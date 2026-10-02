package com.vmse.integration;

import com.vmse.VmsePlugin;
import java.util.concurrent.atomic.AtomicReference;
import ru.dimaskama.voicemessages.api.VoiceMessagesApi;
import ru.dimaskama.voicemessages.api.VoiceMessagesApiInitCallback;

public final class VoiceMessagesIntegration {
    private final VmsePlugin plugin;
    private final AtomicReference<VoiceMessagesApi> api = new AtomicReference<>();

    public VoiceMessagesIntegration(VmsePlugin plugin) {
        this.plugin = plugin;
    }

    public void register() {
        VoiceMessagesApiInitCallback.EVENT.register(api -> {
            this.api.set(api);
            plugin.getLogger().info("Voice Messages API connected and ready for /dmc vmsg.");
        });
    }

    public VoiceMessagesApi getApi() {
        return api.get();
    }

    public boolean isReady() {
        return api.get() != null;
    }
}
