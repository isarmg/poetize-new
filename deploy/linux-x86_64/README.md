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
  --bind 127.0.0.1:8081 --trusted-proxies 127.0.0.1
```

让 HTTPS 反向代理将所有路径（包括 `/api/v1/`）转发至 `127.0.0.1:8081`。前台位于 `/`，管理后台位于 `/admin`。生产保持安全 Cookie，不能使用 `--development-http`。通过 `./bin/xocs --data-dir /var/lib/xocs status --json` 读取真实 readiness 和服务身份；停止服务使用 SIGTERM 并等待任务、数据库与锁正常释放。

配置也可放在服务用户拥有的 `0700` 父目录下，JSON 文件权限为 `0600` 且只有一个硬链接，例如 `{"data_dir":"/var/lib/xocs","bind":"127.0.0.1:8081","trusted_proxies":["127.0.0.1"]}`。使用 `--config /absolute/private/config.json` 运行各命令。默认值、文件、显式环境映射与 CLI 每层均校验，坏低层不会被高层覆盖隐藏，修改配置后重启。

## 代理来源与当前数据库

`trusted_proxies` 是精确连接对端 IP 白名单，默认空。此项仅影响匿名评论来源识别；弹幕和文章访问密码尝试仍按连接对端限流。示例中由本机 Nginx 直接接收访客连接，在 HTTPS server 的转发位置覆盖来源头：

```nginx
location / {
    proxy_pass http://127.0.0.1:8081;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-Proto https;
}
```

不能直接透传访客提交的 `X-Real-IP`，也不能将任意网段视为可信代理。xocs 仅在对端精确匹配时读取单个有效 `X-Real-IP`，不解析 `Forwarded`/`X-Forwarded-For` 链；可信代理的缺失或错误头将导致评论请求被拒绝。默认直连模式忽略这些头。多级代理需要管理员在最后一跳可靠确定访客地址；不要直接套用单级示例。

当前格式为 `xocs-db-v2`（结构修订 2），含评论的持久提交回执，指纹由实际 DDL 生成。其他结构会拒绝且不改写；停止旧进程后，在新的私有目录初始化当前结构即可，不提供旧格式兼容或迁移。评论接口要求每次用户提交提供一个 UUIDv4 `request_id`，结果未知时复用同一身份与内容确认，不能换新身份盲目重发。
