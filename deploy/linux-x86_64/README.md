# Poetize Linux x86-64 部署包

此包包含 Rust 服务程序和前台、管理后台静态文件。数据库表结构已编入服务程序。

## 初始化

需要 Linux x86-64 和 HTTPS 反向代理。解压后进入包目录，再创建仅站点用户可访问的数据目录：

```bash
install -d -m 700 /var/lib/poetize /var/lib/poetize/media
read -rs -p '管理员密码: ' POETIZE_PASSWORD; printf '\n'
printf '%s\n' "$POETIZE_PASSWORD" | ./bin/poetize-rs init \
  --database /var/lib/poetize/site.sqlite --username admin
unset POETIZE_PASSWORD
./bin/poetize-rs doctor --database /var/lib/poetize/site.sqlite
```

`init` 仅能操作尚不存在的数据库。运行服务的用户需要对数据库、媒体目录有读写权限；数据目录与媒体目录的权限必须为 `0700`，数据库文件必须为 `0600`。

## 启动

```bash
./bin/poetize-rs serve \
  --database /var/lib/poetize/site.sqlite \
  --media /var/lib/poetize/media \
  --web "$(pwd)/web" \
  --bind 127.0.0.1:8081
```

让 HTTPS 反向代理将所有路径（包括 `/api/v2/` 和 WebSocket）转发至 `127.0.0.1:8081`。前台位于 `/`，管理后台位于 `/admin`。定期备份 SQLite 数据库与媒体目录。

旧版 Java/MySQL 数据不会自动转换为 SQLite；升级前请保留旧版数据和部署包。
