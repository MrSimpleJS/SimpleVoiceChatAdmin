package com.simple.voiceadmin;

import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.packets.MicrophonePacket;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public class BroadcastVoicechatPlugin implements VoicechatPlugin {
  private final VoiceChatAdminPlugin plugin;
  private final String permission;
  private final String groupName;

  public BroadcastVoicechatPlugin(VoiceChatAdminPlugin plugin, String permission, String groupName) {
    this.plugin = plugin;
    this.permission = permission == null ? "" : permission;
    this.groupName = groupName == null ? "broadcast" : groupName;
  }

  @Override
  public String getPluginId() {
    return "voicechat-admin-broadcast";
  }

  @Override
  public void initialize(VoicechatApi api) {
  }

  @Override
  public void registerEvents(EventRegistration registration) {
    registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophone);
  }

  private void onMicrophone(MicrophonePacketEvent event) {
    if (event.getSenderConnection() == null || event.getSenderConnection().getPlayer() == null) {
      return;
    }
    Object rawPlayer = event.getSenderConnection().getPlayer().getPlayer();
    if (!(rawPlayer instanceof Player player)) {
      return;
    }
    if (!permission.isEmpty() && !player.hasPermission(permission)) {
      return;
    }
    Group group = event.getSenderConnection().getGroup();
    if (group == null || group.getName() == null) {
      return;
    }
    if (!group.getName().trim().equalsIgnoreCase(groupName)) {
      return;
    }
    event.cancel();
    VoicechatServerApi api = event.getVoicechat();
    for (Player onlinePlayer : Bukkit.getServer().getOnlinePlayers()) {
      if (onlinePlayer.getUniqueId().equals(player.getUniqueId())) {
        continue;
      }
      VoicechatConnection connection = api.getConnectionOf(onlinePlayer.getUniqueId());
      if (connection == null) {
        continue;
      }
      api.sendStaticSoundPacketTo(connection, ((MicrophonePacket) event.getPacket()).toStaticSoundPacket());
    }
  }
}
