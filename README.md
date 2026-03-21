# SimpleVoiceChatAdmin

Standalone Paper plugin for Simple Voice Chat administration.

## Features

- Join and manage voice chat groups
- Broadcast voice channel
- Player mute, unmute, mute editing, mute list, and auto-unmute
- Voice warnings, notes, history, and export
- Group lock, unlock, create, delete, move, pull, kick, and disconnect tools
- Staff listen mode and silent spy mode
- Localization with built-in `en`, `de`, and `fr` message packs
- Discord webhook audit logging with action-based colors
- Cooldowns and confirmation prompts for sensitive staff actions

## Commands

- `/adminjoin <group>`
- `/broadcastvoice`
- `/vcmute <player> [minutes] [reason]`
- `/vcunmute <player>`
- `/vclist`
- `/vcinfo <player>`
- `/vckick <player>`
- `/vcmove <player> <group>`
- `/vcmutelist`
- `/vchistory <player>`
- `/vcwarn <player> <reason>`
- `/vcnotes <player> [add|remove|list] [text/index]`
- `/vcpurgehistory <player>`
- `/vcexport <player>`
- `/vcmuteedit <player> <minutes|keep> [reason]`
- `/vcmuteall [minutes] [reason]`
- `/vcunmuteall`
- `/vclock <group>`
- `/vcunlock <group>`
- `/vccreate <group>`
- `/vcdelete <group>`
- `/vcpull <group>`
- `/vcdisconnect <player>`
- `/vcreload`
- `/vclisten <group>`
- `/vcunlisten`
- `/vcspy <group>`
- `/vcunspy`

## Build

This project currently expects the Simple Voice Chat API jar at:

`../Your Folder/lib/voicechat-bukkit-2.6.12.jar`

Then run:

```bash
mvn package
```

## Notes

- `vclisten` can notify the target group.
- `vcspy` is silent to the target group, but still logged internally and through the webhook.
- `voicechat-admin.localization.default-locale` controls the default language.
- `voicechat-admin.localization.use-player-locale` lets the plugin use each player's Minecraft locale when a matching pack exists.
- Locale files are loaded from `plugins/SimpleVoiceChatAdmin/locales/*.yml`.
- You can add any number of custom locales by creating files like `locales/es.yml` or `locales/it.yml`.
- `voicechat-admin.localization.folder` lets you change the locale folder name if needed.
- Runtime fallback messages and webhook templates are configurable in `src/main/resources/config.yml`.
