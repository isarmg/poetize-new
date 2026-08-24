# Poetize Linux x86-64 部署包

此压缩包包含后端可执行 JAR、博客/后台前端、IM 前端、数据库初始化 SQL，以及 Linux 启动和部署示例。

## 运行要求

- Linux x86-64
- Java 25
- MySQL 8 或兼容版本
- Nginx（用于托管前端并代理 API/WebSocket）

## 安装

1. 将整个目录放到 `/opt/poetize`。
2. 创建 MySQL 用户并确保它有权创建或访问 `poetize` 数据库。
3. 将 `config/poetize.env.example` 复制为 `/etc/poetize/poetize.env`，修改数据库密码和初始管理员密码。初始管理员密码至少 8 位，并同时包含字母和数字。
4. 直接运行 `POETIZE_ENV_FILE=/etc/poetize/poetize.env ./bin/poetize-server` 验证后端。
5. 按需安装 `config/poetize.service` 到 `/etc/systemd/system/`。
6. 为域名申请 TLS 证书，将 `config/nginx.conf.example` 中的 `example.com` 和证书路径替换为实际值，再安装到 Nginx 的站点配置目录。不要在生产环境去掉 HTTPS：客户端中的 AES 只是协议编码，不能代替 TLS。

后端 HTTP 和 WebSocket 默认只监听 `127.0.0.1`，供同机 Nginx 代理。如果确实采用跨主机反向代理，可分别设置 `SERVER_ADDRESS` 和 `IM_BIND_ADDRESS`，同时必须用防火墙仅允许受信代理主机访问 8081/9324 端口。

数据库为空时，后端会读取包内的 `sql/poetry.sql` 完成初始化。不要把真实密码提交到 Git。
