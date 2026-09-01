#!/bin/bash
set -euo pipefail
export AWS_DEFAULT_REGION=us-west-1

bucket=minecraft-ai-god-928535088750-us-west-1
home=/opt/minecraft

dnf install -y java-25-amazon-corretto-headless
systemctl enable --now amazon-ssm-agent
id minecraft >/dev/null 2>&1 || useradd --system --home-dir "$home" --shell /sbin/nologin minecraft
install -d -o minecraft -g minecraft "$home/mods"

curl -fsSL \
  https://meta.fabricmc.net/v2/versions/loader/26.2/0.19.3/1.1.2/server/jar \
  -o "$home/fabric-server-launch.jar"
curl -fsSL \
  'https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/0.158.0+26.2/fabric-api-0.158.0+26.2.jar' \
  -o "$home/mods/fabric-api.jar"

printf 'eula=true\n' > "$home/eula.txt"
cat > "$home/server.properties" <<'PROPERTIES'
motd=AI God - every chat message is heard
server-port=25565
max-players=10
online-mode=true
white-list=false
view-distance=8
simulation-distance=6
difficulty=normal
enable-rcon=false
enable-query=false
PROPERTIES

cat > /etc/systemd/system/minecraft.service <<'SERVICE'
[Unit]
Description=AI God Minecraft Server
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=minecraft
Group=minecraft
WorkingDirectory=/opt/minecraft
EnvironmentFile=-/etc/minecraft-ai-god.env
ExecStart=/usr/lib/jvm/java-25-amazon-corretto/bin/java -Xms1G -Xmx3G -jar fabric-server-launch.jar nogui
Restart=always
RestartSec=10
TimeoutStopSec=120

[Install]
WantedBy=multi-user.target
SERVICE

cat > /usr/local/bin/minecraft-deploy <<'DEPLOY'
#!/bin/bash
set -euo pipefail
export AWS_DEFAULT_REGION=us-west-1

artifact_uri=$1
temporary=/opt/minecraft/mods/ai-god.jar.new
trap 'rm -f "$temporary"; systemctl start minecraft' EXIT
systemctl stop minecraft || true
aws s3 cp "$artifact_uri" "$temporary"
chown minecraft:minecraft "$temporary"
mv "$temporary" /opt/minecraft/mods/ai-god.jar

api_key=$(aws ssm get-parameter \
  --name /minecraft-ai-god/openai-api-key \
  --with-decryption \
  --query Parameter.Value \
  --output text 2>/dev/null || true)
if [[ -n "$api_key" ]]; then
  umask 077
  printf 'OPENAI_API_KEY=%s\n' "$api_key" > /etc/minecraft-ai-god.env
fi

systemctl start minecraft
sleep 15
systemctl is-active --quiet minecraft
trap - EXIT
DEPLOY
chmod 755 /usr/local/bin/minecraft-deploy

cat > /usr/local/bin/minecraft-backup <<'BACKUP'
#!/bin/bash
set -euo pipefail
export AWS_DEFAULT_REGION=us-west-1

timestamp=$(date -u +%Y%m%dT%H%M%SZ)
archive=/tmp/minecraft-world-$timestamp.tar.gz
trap 'rm -f "$archive"; systemctl start minecraft' EXIT
systemctl stop minecraft
tar -C /opt/minecraft -czf "$archive" world
aws s3 cp "$archive" "s3://minecraft-ai-god-928535088750-us-west-1/backups/$timestamp.tar.gz"
systemctl start minecraft
rm -f "$archive"
trap - EXIT
BACKUP
chmod 755 /usr/local/bin/minecraft-backup

cat > /etc/systemd/system/minecraft-backup.service <<'SERVICE'
[Unit]
Description=Back up the AI God Minecraft world

[Service]
Type=oneshot
ExecStart=/usr/local/bin/minecraft-backup
SERVICE

cat > /etc/systemd/system/minecraft-backup.timer <<'TIMER'
[Unit]
Description=Daily AI God Minecraft world backup

[Timer]
OnCalendar=*-*-* 09:00:00 UTC
Persistent=true

[Install]
WantedBy=timers.target
TIMER

chown -R minecraft:minecraft "$home"
systemctl daemon-reload
systemctl enable minecraft minecraft-backup.timer

if aws s3api head-object --bucket "$bucket" --key latest/ai-god.jar >/dev/null 2>&1; then
  /usr/local/bin/minecraft-deploy "s3://$bucket/latest/ai-god.jar"
else
  systemctl start minecraft
fi
