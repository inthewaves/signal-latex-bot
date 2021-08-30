# Signal LaTeX bot

A Signal bot that replies to incoming messages with LaTeX PNGs.

Try it out by messaging +14046091473 on Signal.

### Installation

These are installations for a Debian-based installation.

Add an `.env` file containing the following:

```plain
DEPLOY_REMOTE=root@<IP or hostname of server to host the bot>
```

As root on a server, run the following:

```bash
# Run these three lines if you haven't install signald already.
echo "deb https://updates.signald.org unstable main" > /etc/apt/sources.list.d/signald.list
curl https://updates.signald.org/apt-signing-key.asc | apt-key add -
apt update && apt install -y signald

useradd -m -s /bin/bash -g signald -b /var/lib signallatexbot
mkdir -p /opt/signallatexbot/deploy /opt/signallatexbot/images
chown signallatexbot /opt/signallatexbot/images
```

Ensure you have an account registered with signald already. Then, inside of `/var/lib/signallatexbot`, add a
`config.json` file owned by `signallatexbot`:

```json
{
  "accountId": "<signald account id>",
  "outputPhotoDirectory": "/opt/signallatexbot/images",
  "avatarFilePath": "<optional path to avatar file>"
}
```

Copy [`signallatexbot.service`](./signallatexbot.service) to `/etc/systemd/system/signallatexbot.service`

Then on your local machine, run

```bash
./deploy-bot.sh
```

Then on the server, as root:

```bash
systemctl enable signallatexbot
systemctl start signallatexbot
```
