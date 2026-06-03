# MCAuth

Paper 1.21 / Java 21 plugin that verifies first-time Minecraft players through a Discord channel before adding them to the server whitelist.

## Build

```bash
mvn package
```

The plugin jar is created at:

```text
target/mcauth-1.0.0.jar
```

## Setup

1. Create a Discord application and bot.
2. Enable the bot's Message Content Intent in the Discord Developer Portal.
3. Invite the bot to your server with permission to read and send messages in the verification channel.
4. Put the built jar in your Paper server's `plugins` directory.
5. Start the server once, then edit `plugins/MCAuth/config.yml`.
6. Set `discord.token` and `discord.channel-id`.
7. Restart the server.

The plugin adds verified players to the whitelist. Enable whitelist enforcement in your server settings, for example with `white-list=true` in `server.properties`.

## Usage

When an unknown player connects, the server rejects the login and shows a verification code. The player sends only that code in the configured Discord channel. If the code is valid and not expired, the plugin adds that Minecraft UUID to the whitelist and stores the Discord user ID that completed the verification.
