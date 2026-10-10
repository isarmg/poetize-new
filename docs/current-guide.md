# xocs 当前实现与运维

个人博客与管理后台。项目使用 Rust 1.99、Axum、SQLite、React 19、TypeScript 和 Vite。后台采用 xcss 的登录、组件和设计规范。前台与后台资源嵌入同一个 Rust 二进制，数据由显式 `init` 从空库初始化。

## 功能

- 博客：可配置的首页栏目与推荐位、文章与分类、标题优先的全文搜索、Markdown 目录与阅读进度、访问密码、评论、收藏、友链、音乐、旅拍和恋爱笔记。
- 匿名互动：文章评论、评论回复、祝福板评论和留言板弹幕均无需账号；后台统一管理内容。
- 管理后台：数据总览、首页栏目顺序、文章和分类标签、用户状态、评论、动态与留言审核、网站设置、友链、恋爱笔记、图片上传及资源管理。

前台账号、注册、个人资料、聊天室及申请入住接口已经移除。当前数据库格式为 `xocs-db-v2`，不接受其他结构，也不提供旧格式兼容或自动迁移。访客发布不使用账号 Cookie，新评论与弹幕的 `user_id` 为空，评论频率按来源 IP 限制；配置可信代理时按代理提供的已校验访客 IP 独立计数。弹幕仍按连接对端限流。

后台使用与其它 Server 相同的公共页头和内容区。主导航分为总览、内容、社区、资源和设置，各组通过二级导航进入管理页面；原有 `/admin#页面` 链接仍可直接访问。统计、表单和分页支持中英文、浅色与深色模式及手机布局。

前台内容区随屏幕展开，宽屏上限为 1920 像素，首页、文章列表、相册与随笔使用自适应网格；手机端自动调整栏目顺序。语言切换和太阳/月亮主题切换位于顶部导航，使用与菜单文字同高的无边框图标。“家”页包含人物与故事介绍、按天累计的相伴时间、相册、日常记录、祝福板与表白墙，支持故事切换、图片预览、内容分页。菜单使用半透明深色背景与磨砂模糊效果，下滑时自动隐藏，上滑或回到顶部时显示；手机展开菜单时保持可见。“首页”点击直接返回博客，鼠标停留 250 毫秒后才展开下拉菜单；手机通过独立按钮展开，键盘可用方向键打开。

## 构建

后台的[账号设置](account-settings.md)作为普通后台菜单页面，使用正式发布的 xcss 账号组件提供用户名、当前密码、新密码、确认新密码和保存按钮。

需要精确的 Node.js 26.7.0、Rust 1.99.0。从项目根目录运行：

```bash
cd web
npm ci
npm run build
cd ..
export XOCS_SOURCE_REVISION="$(git rev-parse HEAD)"
web/node_modules/.bin/xcss-build-server \
  --config xcss-web-build.json --mode release --no-install \
  --rust-only --source-revision "$XOCS_SOURCE_REVISION"
```

初始化数据库。`init` 从标准输入读取管理员密码，且只接受未存在的数据库文件。数据库父目录和媒体目录权限必须是 `0700`，数据库文件权限必须是 `0600`，路径必须是绝对路径。

```bash
install -d -m 700 /var/lib/xocs
read -rs -p '管理员密码: ' XOCS_PASSWORD; printf '\n'
printf '%s\n' "$XOCS_PASSWORD" | target/x86_64-unknown-linux-gnu/release/xocs \
  --data-dir /var/lib/xocs init --username admin
unset XOCS_PASSWORD
target/x86_64-unknown-linux-gnu/release/xocs \
  --data-dir /var/lib/xocs config validate
```

在项目根目录启动本地开发服务：

```bash
target/x86_64-unknown-linux-gnu/release/xocs --data-dir /var/lib/xocs run \
  --bind 127.0.0.1:8081 --development-http
```

访问 `http://127.0.0.1:8081/`，后台在 `/admin`。运行不需要源码目录或独立 Web 目录。生产环境使用 HTTPS 反向代理转发至回环地址，按下方“评论来源与重试契约”显式设置 `--trusted-proxies` 并覆盖 `X-Real-IP`，去掉 `--development-http`；服务随后要求安全 Cookie，并校验管理写操作的来源及 CSRF 令牌。数据库和媒体目录是持久业务状态。

核心入口为 `init`、`run`、`config validate`、`status`、`--help` 和 `--version`。`--data-dir` 可以统一选择含 `site.sqlite` 和 `media/` 的私有状态目录。配置主格式为严格 JSON，覆盖顺序为显式参数、`XOCS_*` 显式映射的环境变量、`--config` 文件、当前默认值；未知字段和错误类型会失败。配置改变需要重启。`status --json` 探测真实应用就绪状态；`release-identity` 和 `web-assets` 分别输出编译身份和资源清单。

当前数据库必须携带 `xocs-db-v2` 身份且实际 SQL 结构与编译指纹一致。运行只接受当前格式，旧数据明确拒绝且不改写。初始化先完成独立暂存数据库，再以不覆盖已有文件的方式发布。

Rust 公共依赖固定官方 xcss 仓库的版本 `=1.0.0` 与完整 revision `b0524c4fb018b5ba4f27ad71bf32b74c8ef0a972`；一个 @xcss/web 包固定同版 `v1.0.0` 官方发行 URL 和真实归档的 lockfile integrity，不读取相邻工作区。Xocs 软件版本为 `1.0.0`，数据库格式身份为 `xocs-db-v2`，软件版本与数据格式版本分别管理。

根目录是唯一 Cargo workspace 与 lock。共同 builder 验证 Linux AMD64 GNU target、真实源码 revision 和实际二进制资源清单，报告真实输出路径；默认正式输出为 `target/x86_64-unknown-linux-gnu/release/xocs`。独立缓存通过 `CARGO_TARGET_DIR` 选择，打包脚本以 `XOCS_RELEASE_CARGO_TARGET_DIR` 指向同一绝对缓存根。正式包要求干净源码、annotated `v1.0.0` 精确指向 HEAD 和匹配该 HEAD 的二进制身份，不允许 unbound 程序打包。

## 验证

```bash
cargo fmt --all -- --check
cargo clippy --locked --all-targets -- -D warnings
cargo test --locked
cargo build --locked
python3 scripts/check-comment-retries.py "${CARGO_TARGET_DIR:-target}/debug/xocs"
cd web
npm run build
npm run test:unit
node tests/language.mjs
node tests/admin-layout.mjs
node tests/public-layout.mjs
node tests/anonymous-comments.mjs
cd ..
bash scripts/smoke.sh
```

浏览器检查需要安装 Chromium 和 Firefox（`cd web && npx playwright install chromium firefox`）。语言与布局检查使用临时 Web 预览和模拟 API；后台检查覆盖导航、浏览器历史、手机布局、深色模式对比度、加载重试和设置保存。前台检查覆盖手机至 2560 像素宽屏、家页面的相册筛选与分页、计时、故事切换、深色模式、空状态和匿名评论。`scripts/smoke.sh` 在本机回环地址启动临时服务，创建临时数据库和媒体目录，执行 API 与浏览器检查，结束时清理。

## 当前中立接口与初始化

当前版本只使用 `.state-instance.lock`、`.state-maintenance.lock`、`.state-maintenance-pending.json` 和 `.state-atomic-` 临时文件前缀；离线升级工具采用 `.release-upgrade` 工作目录。服务身份头为 `x-service`，健康状态中的公共源码修订字段为 `common_revision`。管理会话采用 `__Host-admin-xocs-session`，显式开发模式采用 `admin-xocs-session`；生产 Cookie 的 Secure、HttpOnly、SameSite、Path 和 CSRF 约束继续生效。资源清单格式为 `web-assets-v1`，公共数据库内部表及索引采用 `_common_` 前缀。

这些接口没有旧名称别名或旧版兼容分支。当前数据库为 `xocs-db-v2`、结构修订 `2`，实际 DDL 由 `build.rs` 组合并重新计算指纹。旧结构会明确拒绝且不改写；停止旧进程后，使用新的私有数据目录执行当前 `init` 和 `config validate`。本产品无需配对客户端，不做自动迁移。不能让不同结构的程序共用数据目录，也不能删除锁文件或修改 metadata 强制启动。

## 评论来源与重试契约

直连时默认忽略全部转发头，使用连接对端 IP。HTTPS 反向代理部署需显式配置 `trusted_proxies`，例如 JSON 的 `"trusted_proxies":["127.0.0.1"]`，CLI `--trusted-proxies 127.0.0.1`，或环境变量 `XOCS_TRUSTED_PROXIES='["127.0.0.1"]'`。只允许列出的精确代理 IP，不隐式信任回环、内网或转发链。

可信代理必须覆盖客户端提供的 `X-Real-IP`，发送单个有效 IP；缺失、重复、带逗号或无效值均拒绝。`Forwarded` 和 `X-Forwarded-For` 不参与来源选择。代理入口应直接接收访客连接；多级代理需由管理员在最末一跳可靠解析并覆盖来源。将服务绑定回环或限制为只有可信代理可访问。Nginx 示例见[部署说明](../deploy/linux-x86_64/README.md)。匿名评论限额为每个来源 IP 每 15 分钟 10 条，文章、祝福板和留言评论共用该访客额度。

三个评论 POST 入口都要求 `request_id`（小写 UUIDv4）。评论首尾按 Unicode `White_Space` 去空白（与 Rust `str::trim` 一致），保留正文内部空白及 U+FEFF。一次用户提交生成一个身份，内容、所属文章/板块和回复目标不变时重试必须复用它；新提交才生成新身份。服务在一个事务中写入评论、限额和持久回执；并发或重启后的相同重试返回原评论且不再占用额度。同一身份配不同内容返回 409。回执不随评论删除：原评论已删除时重试返回 409，不重新发表；回执仅存请求摘要与评论引用。

浏览器在发送前将身份和草稿存入当前标签页的 sessionStorage。网络中断、服务异常或无效成功响应显示“尚未确认”，锁定原稿并提供“确认原提交”，不会自动重发。刷新当前标签页后可继续确认；成功后清空待确认状态。明确的首次拒绝保留可编辑草稿。关闭标签页会结束浏览器侧记录；主动清除待确认状态需先确认“评论可能已经发表”的提示并清空草稿。无法保存记录时不会发送。前端所有公开成功响应均经过当前读模型校验，结构无效时报告独立的响应契约错误。
