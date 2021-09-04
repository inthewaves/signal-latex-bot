# Signal LaTeX bot

A Signal bot that replies to incoming messages with LaTeX PNGs via
[JLaTeXMath](https://github.com/opencollab/jlatexmath).

Try it out by messaging +14046091473 on Signal.

![An example of the LaTeX bot output](./images/latexbotexample.png)

### Installation

These are instructions intended for a Debian 10 installation.

First, on your local machine, clone this repository.

Then, on the server, install podman 3.3.0 and dependencies for running the bot. For a Debian 10 server, run these
commands as root (355 MB of disk space will be used with these commands):

```bash
apt install curl
echo 'deb https://deb.debian.org/debian buster-backports main' >> /etc/apt/sources.list
echo 'deb https://download.opensuse.org/repositories/devel:/kubic:/libcontainers:/testing/Debian_Unstable/ /' > /etc/apt/sources.list.d/devel:kubic:libcontainers:testing.list
curl -L https://download.opensuse.org/repositories/devel:kubic:libcontainers:testing/Debian_Unstable/Release.key | apt-key add -
echo "deb https://updates.signald.org unstable main" > /etc/apt/sources.list.d/signald.list
curl https://updates.signald.org/apt-signing-key.asc | apt-key add -
apt update && apt -y upgrade
apt -t buster-backports install -y libseccomp2
apt install -y podman openjdk-11-jre signald
```

For Ubuntu 20.04:

```bash
echo 'deb https://download.opensuse.org/repositories/devel:/kubic:/libcontainers:/testing/xUbuntu_20.04/ /' > /etc/apt/sources.list.d/devel:kubic:libcontainers:testing.list
curl -L https://download.opensuse.org/repositories/devel:/kubic:/libcontainers:/testing/xUbuntu_20.04/Release.key | apt-key add
echo "deb https://updates.signald.org unstable main" > /etc/apt/sources.list.d/signald.list
curl https://updates.signald.org/apt-signing-key.asc | apt-key add -
apt update && apt -y install podman openjdk-11-jre signald
```

As per https://github.com/containers/podman/issues/6365#issuecomment-633067487, Podman containers won't support memory
limiting without add the following in `/etc/default/grub`

```bash
GRUB_CMDLINE_LINUX="cgroup_enable=memory swapaccount=1"
```

After editing, run `update-grub2` as root and reboot.

For other distributions, refer to https://build.opensuse.org/package/show/devel:kubic:libcontainers:testing/podman, or
monitor https://build.opensuse.org/project/show/devel:kubic:libcontainers:stable to see when version >= 3.3.0 will be
available.

Register with signald via `signald account register`; you may need to get a captcha token for registration
(https://signald.org/articles/captcha/).

As root on the server, run the following:

```bash
useradd -m -s /bin/bash -g signald -b /var/lib signallatexbot
mkdir -p /opt/signallatexbot/deploy /opt/signallatexbot/images
chown signallatexbot /opt/signallatexbot/images
```

Then, we will need to generate the bot configuration. On your local machine, build the bot with `./gradlew installDist`.
After that, run `build/install/signal-latex-bot/bin/signal-latex-bot update-config --local` and follow the prompts. Use
`/opt/signallatexbot/images` for the output photo directory. Upload the resulting `config.json` file to
`/var/lib/signallatexbot` and ensure that it is owned by the `signallatexbot` user.

On your local machine, add an `.env` file in the root of this repo containing the following:

```plain
DEPLOY_REMOTE=root@<IP or hostname of server to host the bot>
```

This is required for the deployment script.

Next, copy [`signallatexbot.service`](./signallatexbot.service) to `/etc/systemd/system/signallatexbot.service`. Then on
your local machine, run `./deploy-bot.sh` to start the bot.

Run the following on the server as root to configure the bot's Signal profile:

```bash
# Be the bot
su - signallatexbot
/opt/signallatexbot/deploy/bin/signal-latex-bot update-profile
exit
```

Finally, to ensure the bot starts on boot, run as root on the server `systemctl enable signallatexbot`.

You can monitor the bot's logs with `journalctl -xefu signallatexbot`.
