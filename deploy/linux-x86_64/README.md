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
6. 按实际域名调整 `config/nginx.conf.example`，再安装到 Nginx 的站点配置目录。

数据库为空时，后端会读取包内的 `sql/poetry.sql` 完成初始化。不要把真实密码提交到 Git。
