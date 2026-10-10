# xocs

个人博客与管理后台。项目使用 Rust 1.99、Axum、SQLite、React 19、TypeScript 和 Vite。后台采用 xcss 的登录、组件和设计规范。前台与后台资源嵌入同一个 Rust 二进制，数据由显式 `init` 从空库初始化。

## 功能

- 博客：可配置的首页栏目与推荐位、文章与分类、标题优先的全文搜索、Markdown 目录与阅读进度、访问密码、评论、收藏、友链、音乐、旅拍和恋爱笔记。
- 匿名互动：文章评论、评论回复、祝福板评论和留言板弹幕均无需账号；后台统一管理内容。
- 管理后台：数据总览、首页栏目顺序、文章和分类标签、用户状态、评论、动态与留言审核、网站设置、友链、恋爱笔记、图片上传及资源管理。

前台账号、注册、个人资料、聊天室及申请入住接口已经移除。现有 `xocs-db-v1` 数据结构保留兼容，历史作者、评论与媒体继续展示；保留的旧表不再提供账号或聊天能力。访客发布不使用账号 Cookie，新评论与弹幕的 `user_id` 为空，发布频率按来源 IP 限制。

后台使用与其它 Server 相同的公共页头和内容区。主导航分为总览、内容、社区、资源和设置，各组通过二级导航进入管理页面；原有 `/admin#页面` 链接仍可直接访问。统计、表单和分页支持中英文、浅色与深色模式及手机布局。

前台内容区随屏幕展开，宽屏上限为 1920 像素，首页、文章列表、相册与随笔使用自适应网格；手机端自动调整栏目顺序。语言切换和太阳/月亮主题切换位于顶部导航，使用与菜单文字同高的无边框图标。“家”页包含人物与故事介绍、按天累计的相伴时间、相册、日常记录、祝福板与表白墙，支持故事切换、图片预览、内容分页。菜单使用半透明深色背景与磨砂模糊效果，下滑时自动隐藏，上滑或回到顶部时显示；手机展开菜单时保持可见。“首页”点击直接返回博客，鼠标停留 250 毫秒后才展开下拉菜单；手机通过独立按钮展开，键盘可用方向键打开。

## 构建

后台的[账号设置](docs/account-settings.md)作为普通后台菜单页面，使用正式发布的 xcss 账号组件提供用户名、当前密码、新密码、确认新密码和保存按钮。

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

访问 `http://127.0.0.1:8081/`，后台在 `/admin`。运行不需要源码目录或独立 Web 目录。生产环境使用 HTTPS 反向代理转发至回环地址，并去掉 `--development-http`；服务随后要求安全 Cookie，并校验管理写操作的来源及 CSRF 令牌。数据库和媒体目录是持久业务状态。

核心入口为 `init`、`run`、`config validate`、`status`、`--help` 和 `--version`。`--data-dir` 可以统一选择含 `site.sqlite` 和 `media/` 的私有状态目录。配置主格式为严格 JSON，覆盖顺序为显式参数、`XOCS_*` 显式映射的环境变量、`--config` 文件、当前默认值；未知字段和错误类型会失败。配置改变需要重启。`status --json` 探测真实应用就绪状态；`release-identity` 和 `web-assets` 分别输出编译身份和资源清单。

当前数据库必须携带 `xocs-db-v1` 身份且实际 SQL 结构与编译指纹一致。运行只接受当前格式，旧数据明确拒绝且不改写。初始化先完成独立暂存数据库，再以不覆盖已有文件的方式发布。

Rust 公共依赖固定官方 xcss 仓库的版本 `=1.0.0` 与完整 revision `9637806055b7d7a18be206f0b83e9b22b73902db`；一个 @xcss/web 包固定同版 `v1.0.0` 官方发行 URL 和真实归档的 lockfile integrity，不读取相邻工作区。Xocs 软件版本为 `1.0.0`，数据库格式身份仍为 `xocs-db-v1`，软件版本与数据格式版本分别管理。

根目录是唯一 Cargo workspace 与 lock。共同 builder 验证 Linux AMD64 GNU target、真实源码 revision 和实际二进制资源清单，报告真实输出路径；默认正式输出为 `target/x86_64-unknown-linux-gnu/release/xocs`。独立缓存通过 `CARGO_TARGET_DIR` 选择，打包脚本以 `XOCS_RELEASE_CARGO_TARGET_DIR` 指向同一绝对缓存根。正式包要求干净源码、annotated `v1.0.0` 精确指向 HEAD 和匹配该 HEAD 的二进制身份，不允许 unbound 程序打包。

## 验证

```bash
cargo fmt --all -- --check
cargo clippy --locked --all-targets -- -D warnings
cargo test --locked
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

本项目采用 [MIT 许可证](LICENSE)。第三方 Markdown 与净化器的许可证位于 `web/licenses/`。

版本改动见 [1.0.0 说明](docs/releases/1.0.0.md)，发行物见 [GitHub Releases](https://github.com/isarmg/xocs/releases)。发布流程验证固定依赖、Rust、双浏览器界面及正式二进制的业务烟测后，生成 Linux x86_64 归档和 SHA-256 校验文件。本次重建仅保留 `v1.0.0` 标签与发行物。

当前发布版本：**1.0.0**。参见 [1.0.0 发布说明](docs/releases/1.0.0.md)。

公共支撑的职责、单体依赖、平台边界与验证方法见[公共支撑说明](docs/common-support.md)。

## 当前中立接口与旧版数据处理

当前版本只使用 `.state-instance.lock`、`.state-maintenance.lock`、`.state-maintenance-pending.json` 和 `.state-atomic-` 临时文件前缀；离线升级工具采用 `.release-upgrade` 工作目录。服务身份头为 `x-service`，健康状态中的公共源码修订字段为 `common_revision`。管理会话采用 `__Host-admin-xocs-session`，显式开发模式采用 `admin-xocs-session`；生产 Cookie 的 Secure、HttpOnly、SameSite、Path 和 CSRF 约束继续生效。资源清单格式为 `web-assets-v1`，公共数据库内部表及索引采用 `_common_` 前缀。

这些接口没有旧名称别名或旧版兼容分支。旧版升级前，先按本文的停服步骤停止服务及全部维护工具；确认全部进程退出后，完整备份配置、SQLite 数据库及其 WAL/SHM、业务文件和必要的私有凭据。备份包含敏感数据，应保留原有访问权限并离线保存。

保留旧数据目录，按当前安装步骤配置新的私有数据目录，执行显式 `init` 初始化，随后运行 `config validate`，再启动服务并登录管理页面；本产品无需配对客户端。旧配置应人工审阅后填写当前字段，不能整体覆盖新目录。旧业务数据需要另行处理；当前版本不提供自动迁移。不得让旧、新版本同时写同一目录，不得通过删锁文件或修改数据库 metadata 强制启动；当前结构指纹包含实际表名、索引名和 SQL，仅改名称不能证明数据符合当前合同。
