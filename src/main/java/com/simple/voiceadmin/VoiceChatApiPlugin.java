package com.simple.voiceadmin;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;

public class VoiceChatApiPlugin implements VoicechatPlugin {
  private final VoiceChatAdminPlugin plugin;
  private VoicechatServerApi voicechatApi;

  public VoiceChatApiPlugin(VoiceChatAdminPlugin plugin) {
    this.plugin = plugin;
  }

  @Override
  public String getPluginId() {
    return "voicechat-admin-plugin";
  }

  @Override
  public void initialize(VoicechatApi api) {
    if (api instanceof VoicechatServerApi serverApi) {
      this.voicechatApi = serverApi;
      plugin.getLogger().info("VoiceChat API initialized.");
    } else {
      plugin.getLogger().warning("VoiceChat API is not available.");
    }
  }

  public VoicechatServerApi getVoicechatApi() {
    return voicechatApi;
  }
}
