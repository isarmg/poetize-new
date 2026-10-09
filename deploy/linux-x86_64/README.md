# Xocs Linux x86-64 部署包

此包的 Rust 服务程序内嵌前台、管理后台静态资源及当前数据库结构。运行时不需要独立 Web 目录。

## 初始化

需要 Linux x86-64 和 HTTPS 反向代理。解压后进入包目录，再创建仅站点用户可访问的数据目录：

```bash
install -d -m 700 /var/lib/xocs
read -rs -p '管理员密码: ' XOCS_PASSWORD; printf '\n'
printf '%s\n' "$XOCS_PASSWORD" | ./bin/xocs \
  --data-dir /var/lib/xocs init --username admin
unset XOCS_PASSWORD
./bin/xocs --data-dir /var/lib/xocs config validate
```

`init` 仅能操作明确选择的空数据目录和未存在的数据库，不覆盖现有状态。运行服务的用户必须拥有数据目录、媒体目录和日志目录，权限为 `0700`；数据库文件必须为 `0600`。`run` 永不创建数据库或管理员。已有库应先执行 `config validate`；它只读检查当前 WAL 代和完整结构、管理员及凭据，不修改原文件。

## 启动

```bash
./bin/xocs --data-dir /var/lib/xocs run \
  --bind 127.0.0.1:8081
```

让 HTTPS 反向代理将所有路径（包括 `/api/v1/`）转发至 `127.0.0.1:8081`。前台位于 `/`，管理后台位于 `/admin`。生产保持安全 Cookie，不能使用 `--development-http`。通过 `./bin/xocs --data-dir /var/lib/xocs status --json` 读取真实 readiness 和服务身份；停止服务使用 SIGTERM 并等待任务、数据库与锁正常释放。

配置也可放在服务用户拥有的 `0700` 父目录下，JSON 文件权限为 `0600` 且只有一个硬链接，例如 `{"data_dir":"/var/lib/xocs","bind":"127.0.0.1:8081"}`。使用 `--config /absolute/private/config.json` 运行各命令。默认值、文件、显式环境映射与 CLI 每层均校验，坏低层不会被高层覆盖隐藏，修改配置后重启。
