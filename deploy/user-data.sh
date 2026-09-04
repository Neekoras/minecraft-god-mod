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
motd=Minecraft, with ChatGPT.
server-port=25565
max-players=10
online-mode=true
enforce-secure-profile=false
white-list=false
view-distance=8
simulation-distance=6
difficulty=normal
level-seed=42203442493
spawn-protection=0
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
SuccessExitStatus=143
Restart=always
RestartSec=10
TimeoutStopSec=120

[Install]
WantedBy=multi-user.target
SERVICE

aws s3 cp "s3://$bucket/latest/minecraft-deploy" /usr/local/bin/minecraft-deploy
chmod 755 /usr/local/bin/minecraft-deploy

chown -R minecraft:minecraft "$home"
systemctl daemon-reload
systemctl enable minecraft

if aws s3api head-object --bucket "$bucket" --key latest/ai-god.jar >/dev/null 2>&1; then
  if aws s3api head-object --bucket "$bucket" --key latest/server-icon.png >/dev/null 2>&1; then
    aws s3 cp "s3://$bucket/latest/server-icon.png" "$home/server-icon.png"
    chown minecraft:minecraft "$home/server-icon.png"
  fi
  /usr/local/bin/minecraft-deploy "s3://$bucket/latest/ai-god.jar"
else
  systemctl start minecraft
fi
