These are instructions for a Fedora 34 server installation.

First, on your local machine, clone this repository.

Then, on the server, install dependencies for running the bot:

```bash
dnf update
dnf install -y podman java-11-openjdk policycoreutils-python-utils
```

### Setting up signald

If you already have signald setup on your Fedora 34 server, you can skip this section.

Setup the signald user (`#` indicates running as root; `$` indicates running as the signald user). Parts of this were
taken from
https://blog.christophersmart.com/2021/02/20/rootless-podman-containers-under-system-accounts-managed-and-enabled-at-boot-with-systemd/:

```
# useradd -r -m -s /bin/bash -b /var/lib signald
# su - signald -c "mkdir -m 755 ~/data"
# su - signald -c "mkdir -m 755 ~/uploads"
# loginctl enable-linger signald
# semanage fcontext --add --type user_home_dir_t "/var/lib/signald(/.+)?"
# semanage fcontext --add --type container_file_t "/var/lib/signald/data(/.+)?"
# semanage fcontext --add --type container_file_t "/var/lib/signald/uploads(/.+)?"
# restorecon -Frv /var/lib/signald/
# chmod 755 /var/lib/signald/
# su - signald
$ podman run -u 0:0 -d --name signald -v /var/lib/signald/data:/signald:z -v /var/lib/signald/uploads:/var/lib/signald/uploads:z signald/signald
$ mkdir -p ~/.config/systemd/user
$ podman generate systemd --restart-policy always --name signald > ~/.config/systemd/user/signald.service
$ export XDG_RUNTIME_DIR=/run/user/"$(id -u)"
$ systemctl --user daemon-reload
$ systemctl --user enable --now signald.service
$ exit
# ln -s /var/lib/signald/data/signald.sock /var/run/signald
```
<!-- Troubleshooting: restorecon -R -v /var/lib/signald/.local/share/containers/ -->

Install signaldctl for root:

```
curl -Lo /bin/signaldctl https://gitlab.com/api/v4/projects/21018340/jobs/artifacts/main/raw/signaldctl?job=build%3Ax86
chmod +x /bin/signaldctl
mkdir -p ~/.config
```

### Setting up the Signal LaTeX bot user

Setup bot user and uploads directory:

```
useradd -m -s /bin/bash -b /var/lib signallatexbot
mkdir -p /opt/signallatexbot/deploy
su - signald -c "mkdir -m 770 ~/uploads/signallatexbot"
chgrp signallatexbot /var/lib/signald/uploads/signallatexbot/
```

Deploy the bot

# Known issues

## systemd unit file

A known unit file that works:

```unit file (systemd)
[Unit]
Description=Signal LaTeX bot
#Requires=signald.service

[Service]
#CapabilityBoundingSet=
ExecStart=/usr/bin/java -cp '/opt/signallatexbot/deploy/lib/*' signallatexbot.MainKt run --socket-path /var/lib/signald/data/signald.sock --socket-connect-retries 10
# /opt/signallatexbot/deploy/bin/signal-latex-bot --socket-connect-retries 10
#LockPersonality=true
#NoNewPrivileges=true
#PrivateDevices=true
#PrivateIPC=true
#PrivateTmp=true
#PrivateUsers=true
#ProtectClock=true
#ProtectControlGroups=true
#ProtectHome=true
#ProtectHostname=true
#ProtectKernelLogs=true
#ProtectKernelModules=true
#ProtectKernelTunables=true
#ProtectProc=invisible
#ProtectSystem=strict
#ReadWritePaths=/var/tmp /tmp /var/lib/signallatexbot /var/lib/signald/uploads/signallatexbot 
#ReadOnlyPaths=/opt/signallatexbot/deploy
#RestrictAddressFamilies=AF_UNIX
#RestrictNamespaces=true
#RestrictRealtime=true
#RestrictSUIDSGID=true
#SystemCallArchitectures=native
#SystemCallFilter=@system-service
#SystemCallFilter=~@privileged @resources
UMask=0077
User=signallatexbot
WorkingDirectory=/var/lib/signallatexbot

[Install]
WantedBy=multi-user.target
```

Might be better to run the LaTeX generator in a separate user, using something like a UNIX socket or some other IPC
mechanism to communicate LaTeX requests to the generator process. These sandboxing options make podman not work
properly, plus there are issues with the bot process running `ProcessBuilder.execute`.
