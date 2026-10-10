# 配置参考

所有命令共享严格 JSON 配置：`xocs --config /absolute/private/config.json ...`。
优先级为显式 CLI、下表环境变量、文件、默认值。每层都验证字段与类型；修改配置后重启进程。

## 常用字段

| JSON 字段 | 默认值 | 用途与取值 | CLI / 环境变量 |
|---|---|---|---|
| `data_dir` | 无 | 私有绝对目录，默认在其中使用 `site.sqlite` 和 `media/` | `--data-dir` / `XOCS_DATA_DIR` |
| `database` | 由 `data_dir` 派生 | SQLite 绝对路径；同时指定 `data_dir` 时须为其直接子文件 | `--database` / `XOCS_DATABASE` |
| `media` | `data_dir/media`，或数据库旁 `media` | 媒体私有绝对目录 | `--media` / `XOCS_MEDIA` |
| `bind` | `127.0.0.1:8081` | IP 和端口 | `--bind` / `XOCS_BIND` |
| `trusted_proxies` | `[]` | 最多 32 个精确代理 IP，影响匿名评论来源 | `--trusted-proxies` / `XOCS_TRUSTED_PROXIES` |
| `development_http` | `false` | 本机回环开发 HTTP | `--development-http` / `XOCS_DEVELOPMENT_HTTP` |
| `web` | 无，使用内嵌资源 | 显式开发模式的资源绝对目录 | `--web` / `XOCS_DEV_WEB_DIR` |

`XOCS_CONFIG` 也可指定配置文件。代理环境变量使用 JSON 数组，CLI 使用逗号分隔 IP。

```json
{
  "data_dir": "/var/lib/xocs",
  "bind": "127.0.0.1:8081",
  "trusted_proxies": ["127.0.0.1"],
  "development_http": false
}
```

配置文件由运行用户拥有，权限 `0600`，位于 `0700` 私有父目录中，使用单硬链接普通文件。
配置放在媒体根之外；状态、媒体和配置路径使用绝对物理路径。程序会检查实际文件及目录权限。

## HTTPS 与评论来源

生产将服务绑定回环地址，由 HTTPS 代理转发。只有实际连接对端精确匹配 `trusted_proxies` 时，程序才读取一个有效的 `X-Real-IP`。
代理必须覆盖访客传入的同名头；缺失、重复、逗号列表或非法值会使评论请求失败。
`Forwarded` 和 `X-Forwarded-For` 不参与来源选择。多级代理应在最后一跳可靠确定访客地址后覆盖该头。

此配置只影响匿名评论：弹幕和文章访问密码尝试仍按连接对端限流。
完整 nginx 示例见[部署手册](../deploy/linux-x86_64/README.md#配置-https-和评论来源)。

## 检查生效配置

```sh
xocs --config /absolute/private/config.json config validate --json
xocs --config /absolute/private/config.json status --json
```

第一条只读校验配置和当前状态；第二条探测实际 readiness 和服务身份。退出码为 0 才表示该项检查成功。
`init` 用于首次创建，`run` 用于运行已有站点，`--help` 和 `--version` 可在初始化前查询。
