# POETIZE

个人博客、社区与管理后台。项目使用 Rust 1.98、Axum、SQLite、React 19、TypeScript 和 Vite。后台采用 Sarmg Foundation 的登录、组件和设计规范。前台与后台由同一个 Rust 服务提供，数据从空库初始化。

## 功能

- 博客：可配置的首页栏目与推荐位、文章与分类、标题优先的全文搜索、Markdown 目录与阅读进度、访问密码、评论、收藏、友链、音乐、旅拍和恋爱笔记。
- 会员与聊天：注册、登录、资料与密码管理，图片动态与留言、好友申请、私信、群组、历史消息和 WebSocket 实时通知。
- 管理后台：数据总览、首页栏目顺序、文章和分类标签、用户状态、评论、动态与留言审核、网站设置、友链、恋爱笔记、图片上传及资源管理。

## 构建

需要 Node.js 26.7+、Rust 1.98+。从项目根目录运行：

```bash
cd poetize-web
npm ci
npm run build
cd ../poetize-rs
cargo build --locked --release
```

初始化数据库。`init` 从标准输入读取管理员密码，且只接受未存在的数据库文件。数据库父目录和媒体目录权限必须是 `0700`，数据库文件权限必须是 `0600`，路径必须是绝对路径。

```bash
install -d -m 700 /var/lib/poetize /var/lib/poetize/media
read -rs -p '管理员密码: ' POETIZE_PASSWORD; printf '\n'
printf '%s\n' "$POETIZE_PASSWORD" | poetize-rs/target/release/poetize-rs init \
  --database /var/lib/poetize/site.sqlite --username admin
unset POETIZE_PASSWORD
poetize-rs/target/release/poetize-rs doctor --database /var/lib/poetize/site.sqlite
```

在项目根目录启动本地开发服务：

```bash
poetize-rs/target/release/poetize-rs serve \
  --database /var/lib/poetize/site.sqlite \
  --media /var/lib/poetize/media \
  --web "$(pwd)/poetize-web/dist" \
  --bind 127.0.0.1:8081 --development-http
```

访问 `http://127.0.0.1:8081/`，后台在 `/admin`。生产环境使用 HTTPS 反向代理转发至回环地址，并去掉 `--development-http`；服务随后要求安全 Cookie，并校验管理和会员写操作的来源及 CSRF 令牌。请备份 SQLite 数据库与媒体目录。

## 验证

```bash
cd poetize-rs
cargo fmt --all -- --check
cargo clippy --locked --all-targets -- -D warnings
cargo test --locked
cd ../poetize-web
npm run build
cd ..
bash poetize-rs/scripts/smoke.sh
```

端到端测试需要安装 Chromium（`cd poetize-web && npx playwright install chromium`），并在本机回环地址启动临时服务。它会创建临时数据库和媒体目录，执行 API 与浏览器检查，结束时清理。

本项目采用 [MIT 许可证](LICENSE)。第三方 Markdown 与净化器的许可证位于 `poetize-web/src/vendor/`。
