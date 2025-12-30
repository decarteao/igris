#!/bin/

# I delete the build app ':)

cd /root
apt update -y

wget https://github.com/decarteao/igris/raw/refs/heads/main/server/igrisserver
chmod +x igrisserver

cat > /etc/systemd/system/igrisserver.service <<-END
[Unit]
Description=IgrisServer
After=network.target

[Service]
ExecStart=/root/igrisserver
WorkingDirectory=/root/
StandardOutput=inherit
StandardError=inherit
Restart=on-failure
LimitNOFILE=200000
LimitNPROC=65536
TasksMax=infinity

[Install]
WantedBy=multi-user.target
END

systemctl daemon-reload

systemctl enable igrisserver
systemctl restart igrisserver

# aumentar os limites do kernel
ulimit -n 200000

reboot

