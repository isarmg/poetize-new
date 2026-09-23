mod admin;
mod auth;
mod content;
mod home;
mod im;
mod media;

use anyhow::{Context, bail};
use axum::{
    Json, Router,
    extract::{Request, State},
    http::StatusCode,
    middleware::{Next, from_fn_with_state},
    response::{IntoResponse, Response},
    routing::get,
};
use clap::{Parser, Subcommand};
use sarmg_admin_auth::AdministratorOriginMode;
use sarmg_admin_core::AdministratorService;
use sarmg_admin_sqlite::SqliteAdministratorStore;
use sqlx::{
    SqlitePool,
    sqlite::{SqliteConnectOptions, SqliteJournalMode, SqlitePoolOptions},
};
use std::{
    fs::{self, OpenOptions},
    io::{self, Read},
    net::SocketAddr,
    os::unix::fs::{OpenOptionsExt, PermissionsExt},
    path::PathBuf,
    str::FromStr,
    sync::Arc,
    time::{Duration, SystemTime, UNIX_EPOCH},
};
use tower_http::{
    services::{ServeDir, ServeFile},
    trace::TraceLayer,
};

const PRODUCT_ID: &str = "poetize";

#[derive(Parser)]
#[command(about = "POETIZE Rust service")]
struct Cli {
    #[command(subcommand)]
    command: Command,
}

#[derive(Subcommand)]
enum Command {
    /// Create a new empty database. Read the administrator password from stdin.
    Init {
        #[arg(long)]
        database: PathBuf,
        #[arg(long, default_value = "admin")]
        username: String,
    },
    /// Serve an existing database and built web assets.
    Serve {
        #[arg(long)]
        database: PathBuf,
        #[arg(long, default_value = "127.0.0.1:8081")]
        bind: SocketAddr,
        #[arg(long)]
        web: PathBuf,
        #[arg(long)]
        media: PathBuf,
        #[arg(long)]
        development_http: bool,
    },
    Doctor {
        #[arg(long)]
        database: PathBuf,
    },
}

#[derive(Clone)]
struct AppState {
    pool: SqlitePool,
    admin: Arc<AdministratorService<SqliteAdministratorStore>>,
    origin: AdministratorOriginMode,
    events: tokio::sync::broadcast::Sender<im::OutboundEvent>,
    media: PathBuf,
}

#[derive(Debug)]
struct AppError(StatusCode, &'static str);
impl IntoResponse for AppError {
    fn into_response(self) -> Response {
        (self.0, Json(serde_json::json!({"error":self.1}))).into_response()
    }
}
type ApiResult<T> = Result<Json<T>, AppError>;

fn db_error(error: sqlx::Error) -> AppError {
    tracing::error!(%error, "database operation failed");
    AppError(StatusCode::INTERNAL_SERVER_ERROR, "数据库操作失败")
}
fn invalid(message: &'static str) -> AppError {
    AppError(StatusCode::BAD_REQUEST, message)
}
fn absent() -> AppError {
    AppError(StatusCode::NOT_FOUND, "内容不存在")
}
async fn site_author_id(pool: &SqlitePool) -> Result<i64, AppError> {
    sqlx::query_scalar(
        "SELECT id FROM user WHERE user_type=0 AND deleted=0 AND user_status=1 ORDER BY id LIMIT 1",
    )
    .fetch_optional(pool)
    .await
    .map_err(db_error)?
    .ok_or(AppError(StatusCode::CONFLICT, "缺少站点作者账号"))
}
fn now_micros() -> anyhow::Result<u64> {
    Ok(u64::try_from(
        SystemTime::now().duration_since(UNIX_EPOCH)?.as_micros(),
    )?)
}

async fn open_database(path: &PathBuf, create: bool) -> anyhow::Result<SqlitePool> {
    if !path.is_absolute() {
        bail!("database path must be absolute");
    }
    let parent = path
        .parent()
        .context("database path has no parent directory")?;
    let parent_metadata =
        fs::symlink_metadata(parent).context("database parent directory is missing")?;
    if !parent_metadata.is_dir()
        || parent_metadata.file_type().is_symlink()
        || parent_metadata.permissions().mode() & 0o077 != 0
    {
        bail!("database parent directory must be a private 0700 directory");
    }
    if create {
        OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .open(path)
            .context("create private SQLite database")?;
    } else {
        let metadata = fs::symlink_metadata(path).context("database does not exist")?;
        if !metadata.is_file()
            || metadata.file_type().is_symlink()
            || metadata.permissions().mode() & 0o077 != 0
        {
            bail!("database must be a regular private 0600 file");
        }
    }
    let options = SqliteConnectOptions::from_str(&format!("sqlite://{}", path.display()))?
        .create_if_missing(false)
        .foreign_keys(true)
        .journal_mode(SqliteJournalMode::Wal)
        .busy_timeout(Duration::from_secs(5));
    SqlitePoolOptions::new()
        .max_connections(8)
        .connect_with(options)
        .await
        .context("open SQLite database")
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "info,tower_http=info".into()),
        )
        .init();
    match Cli::parse().command {
        Command::Init { database, username } => {
            if database.exists() {
                bail!(
                    "refusing to overwrite existing database: {}",
                    database.display()
                );
            }
            let username = sarmg_admin_auth::normalize_administrator_username(&username)?;
            let mut password = String::new();
            io::stdin().read_to_string(&mut password)?;
            let password = password.trim_end_matches(['\r', '\n']);
            sarmg_admin_auth::validate_password(password)?;
            let pool = open_database(&database, true).await?;
            if let Err(error) = initialize(&pool, &username, password).await {
                pool.close().await;
                let _ = std::fs::remove_file(&database);
                return Err(error);
            }
            pool.close().await;
            println!("created {}", database.display());
        }
        Command::Serve {
            database,
            bind,
            web,
            media,
            development_http,
        } => {
            if !web.join("index.html").is_file() {
                bail!("web directory does not contain index.html");
            }
            if development_http && !bind.ip().is_loopback() {
                bail!("development HTTP may only bind loopback");
            }
            if !media.is_absolute() {
                bail!("media directory path must be absolute");
            }
            let media_metadata =
                fs::symlink_metadata(&media).context("media directory is missing")?;
            if !media_metadata.is_dir()
                || media_metadata.file_type().is_symlink()
                || media_metadata.permissions().mode() & 0o077 != 0
            {
                bail!("media directory must be a private 0700 directory");
            }
            let pool = open_database(&database, false).await?;
            validate_database(&pool).await?;
            let origin = if development_http {
                AdministratorOriginMode::LoopbackDevelopmentHttp
            } else {
                AdministratorOriginMode::ProductionHttps
            };
            let admin = Arc::new(AdministratorService::new(SqliteAdministratorStore::new(
                pool.clone(),
            )));
            admin.store().validate_all_administrators().await?;
            let (events, _) = tokio::sync::broadcast::channel(1024);
            let state = AppState {
                pool,
                admin,
                origin,
                events,
                media,
            };
            let app = router(state, web)?;
            let listener = tokio::net::TcpListener::bind(bind).await?;
            tracing::info!(%bind, "POETIZE listening");
            axum::serve(
                listener,
                app.into_make_service_with_connect_info::<SocketAddr>(),
            )
            .with_graceful_shutdown(async {
                let _ = tokio::signal::ctrl_c().await;
            })
            .await?;
        }
        Command::Doctor { database } => {
            let pool = open_database(&database, false).await?;
            validate_database(&pool).await?;
            SqliteAdministratorStore::new(pool.clone())
                .validate_all_administrators()
                .await?;
            let integrity: String = sqlx::query_scalar("PRAGMA integrity_check")
                .fetch_one(&pool)
                .await?;
            if integrity != "ok" {
                bail!("database integrity check failed: {integrity}");
            }
            println!("ok");
        }
    }
    Ok(())
}

async fn initialize(pool: &SqlitePool, username: &str, password: &str) -> anyhow::Result<()> {
    sqlx::raw_sql(include_str!("../schema.sql"))
        .execute(pool)
        .await?;
    sqlx::query("INSERT INTO user(id,username,user_type) VALUES(1,'site-author',0)")
        .execute(pool)
        .await?;
    let service = AdministratorService::new(SqliteAdministratorStore::new(pool.clone()));
    service
        .bootstrap_administrator(username, password, now_micros()?)
        .await?;
    validate_database(pool).await
}

async fn validate_database(pool: &SqlitePool) -> anyhow::Result<()> {
    let names: Vec<String> =
        sqlx::query_scalar("SELECT name FROM sqlite_schema WHERE type='table'")
            .fetch_all(pool)
            .await?;
    for table in [
        "user",
        "member_sessions",
        "article",
        "comment",
        "sort",
        "label",
        "resource",
        "im_chat_user_message",
        "im_chat_user_group_message",
        "_sarmg_administrators",
        "_sarmg_admin_sessions",
    ] {
        if !names.iter().any(|name| name == table) {
            bail!("missing required table: {table}");
        }
    }
    let violations: Vec<(String, Option<i64>, String, i64)> =
        sqlx::query_as("PRAGMA foreign_key_check")
            .fetch_all(pool)
            .await?;
    if !violations.is_empty() {
        bail!("database contains foreign-key violations");
    }
    let authors: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM user WHERE user_type=0 AND deleted=0 AND user_status=1",
    )
    .fetch_one(pool)
    .await?;
    if authors == 0 {
        bail!("database has no active site author");
    }
    Ok(())
}

fn router(state: AppState, web: PathBuf) -> anyhow::Result<Router> {
    let admin_routes = content::admin_routes()
        .merge(admin::routes())
        .merge(media::admin_routes())
        .merge(home::admin_routes())
        .route_layer(from_fn_with_state(state.clone(), admin_gate))
        .with_state(state.clone());
    let public_routes = content::public_routes()
        .merge(home::public_routes())
        .with_state(state.clone());
    let member_routes = auth::protected_routes()
        .merge(content::member_routes())
        .merge(media::member_routes())
        .merge(im::member_routes())
        .route_layer(from_fn_with_state(state.clone(), auth::member_gate))
        .with_state(state.clone());
    let public_member_routes = auth::public_routes().with_state(state.clone());
    let entry = web.join("index.html");
    Ok(Router::new()
        .route(
            "/api/v2/health",
            get(|| async { Json(serde_json::json!({"status":"ok"})) }),
        )
        .merge(public_routes)
        .merge(public_member_routes)
        .merge(member_routes)
        .merge(im::websocket_routes().with_state(state.clone()))
        .nest_service("/media", ServeDir::new(&state.media))
        .merge(admin_routes)
        .merge(sarmg_admin_axum::administrator_router(
            PRODUCT_ID,
            state.origin,
            Arc::clone(&state.admin),
        )?)
        .route_service("/admin", ServeFile::new(&entry))
        .route_service("/im", ServeFile::new(&entry))
        .route_service("/sort", ServeFile::new(&entry))
        .route_service("/search", ServeFile::new(&entry))
        .route_service("/weiYan", ServeFile::new(&entry))
        .route_service("/jotting", ServeFile::new(&entry))
        .route_service("/menory", ServeFile::new(&entry))
        .route_service("/favorite", ServeFile::new(&entry))
        .route_service("/friend", ServeFile::new(&entry))
        .route_service("/music", ServeFile::new(&entry))
        .route_service("/travel", ServeFile::new(&entry))
        .route_service("/love", ServeFile::new(&entry))
        .route_service("/message", ServeFile::new(&entry))
        .route_service("/about", ServeFile::new(&entry))
        .route_service("/letter", ServeFile::new(&entry))
        .route_service("/user", ServeFile::new(&entry))
        .route_service("/article/{id}", ServeFile::new(&entry))
        .route(
            "/api/{*path}",
            get(|| async { absent() }).fallback(|| async { absent() }),
        )
        .fallback_service(ServeDir::new(web))
        .layer(TraceLayer::new_for_http()))
}

async fn admin_gate(State(state): State<AppState>, request: Request, next: Next) -> Response {
    match sarmg_admin_axum::authenticate_request(
        &state.admin,
        request.headers(),
        request.uri(),
        request.method(),
        PRODUCT_ID,
        state.origin,
    )
    .await
    {
        Ok(_) => next.run(request).await,
        Err(response) => *response,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use axum::{
        body::{Body, to_bytes},
        extract::ConnectInfo,
        http::{Request, header},
    };
    use tower::ServiceExt;

    async fn setup() -> (tempfile::TempDir, Router, SqlitePool) {
        let dir = tempfile::tempdir().unwrap();
        std::fs::set_permissions(dir.path(), std::fs::Permissions::from_mode(0o700)).unwrap();
        let db = dir.path().join("site.sqlite");
        let pool = open_database(&db, true).await.unwrap();
        initialize(&pool, "admin", "TemporaryPassphrase123")
            .await
            .unwrap();
        std::fs::write(dir.path().join("index.html"), "<h1>POETIZE</h1>").unwrap();
        let admin = Arc::new(AdministratorService::new(SqliteAdministratorStore::new(
            pool.clone(),
        )));
        let (events, _) = tokio::sync::broadcast::channel(1024);
        let state = AppState {
            pool: pool.clone(),
            admin,
            origin: AdministratorOriginMode::LoopbackDevelopmentHttp,
            events,
            media: dir.path().to_path_buf(),
        };
        let app = router(state, dir.path().to_path_buf()).unwrap();
        (dir, app, pool)
    }

    async fn body_json(response: Response) -> serde_json::Value {
        let bytes = to_bytes(response.into_body(), 2_000_000).await.unwrap();
        serde_json::from_slice(&bytes).unwrap()
    }
    fn with_peer(mut request: Request<Body>) -> Request<Body> {
        request.extensions_mut().insert(ConnectInfo(
            "127.0.0.1:45678".parse::<SocketAddr>().unwrap(),
        ));
        request
    }

    #[tokio::test]
    async fn public_visibility_and_password_are_enforced() {
        let (_dir, app, pool) = setup().await;
        let hash = sarmg_admin_auth::hash_password("ProtectedPassphrase123").unwrap();
        sqlx::query("INSERT INTO article(id,user_id,sort_id,label_id,article_title,article_content,view_status,password) VALUES(2,1,1,1,'Protected','private body',0,?)")
            .bind(hash).execute(&pool).await.unwrap();
        let list = app
            .clone()
            .oneshot(with_peer(
                Request::builder()
                    .uri("/api/v2/articles")
                    .body(Body::empty())
                    .unwrap(),
            ))
            .await
            .unwrap();
        assert_eq!(list.status(), StatusCode::OK);
        let list = body_json(list).await;
        assert_eq!(list["total"], 1);
        assert!(list.to_string().contains("Protected"));
        assert!(!list.to_string().contains("private body"));
        assert!(list["items"][0]["excerpt"].is_null());
        let stats = app
            .clone()
            .oneshot(
                Request::builder()
                    .uri("/api/v2/site/stats")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(stats.status(), StatusCode::OK);
        assert_eq!(body_json(stats).await["article_count"], 1);
        let public = app
            .clone()
            .oneshot(
                Request::builder()
                    .uri("/api/v2/articles/2")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(public.status(), StatusCode::NOT_FOUND);
        let access = app
            .clone()
            .oneshot(
                Request::builder()
                    .uri("/api/v2/articles/2/access")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(access.status(), StatusCode::OK);
        assert_eq!(body_json(access).await["password_required"], 1);
        let bad = app
            .clone()
            .oneshot(with_peer(
                Request::builder()
                    .method("POST")
                    .uri("/api/v2/articles/2/unlock")
                    .header("content-type", "application/json")
                    .body(Body::from(r#"{"password":"wrong"}"#))
                    .unwrap(),
            ))
            .await
            .unwrap();
        assert_eq!(bad.status(), StatusCode::FORBIDDEN);
        let good = app
            .clone()
            .oneshot(with_peer(
                Request::builder()
                    .method("POST")
                    .uri("/api/v2/articles/2/unlock")
                    .header("content-type", "application/json")
                    .body(Body::from(r#"{"password":"ProtectedPassphrase123"}"#))
                    .unwrap(),
            ))
            .await
            .unwrap();
        assert_eq!(good.status(), StatusCode::OK);
        let body = body_json(good).await.to_string();
        assert!(body.contains("private body"));
        assert!(!body.contains("ProtectedPassphrase123"));
        for attempt in 1..=11 {
            let response = app
                .clone()
                .oneshot(with_peer(
                    Request::builder()
                        .method("POST")
                        .uri("/api/v2/articles/2/unlock")
                        .header("content-type", "application/json")
                        .body(Body::from(r#"{"password":"wrong"}"#))
                        .unwrap(),
                ))
                .await
                .unwrap();
            assert_eq!(
                response.status(),
                if attempt <= 10 {
                    StatusCode::FORBIDDEN
                } else {
                    StatusCode::TOO_MANY_REQUESTS
                }
            );
        }
    }

    #[tokio::test]
    async fn administrator_route_rejects_anonymous_mutation() {
        let (_dir, app, _pool) = setup().await;
        let response = app
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/api/v2/content/articles")
                    .header("content-type", "application/json")
                    .body(Body::from("{}"))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
    }

    #[tokio::test]
    async fn member_can_log_in_and_comment_with_csrf() {
        let (_dir, app, pool) = setup().await;
        let hash = sarmg_admin_auth::hash_password("MemberPassword123").unwrap();
        sqlx::query("INSERT INTO user(id,username,password,user_type) VALUES(2,'member',?,2)")
            .bind(hash)
            .execute(&pool)
            .await
            .unwrap();
        sqlx::query("INSERT INTO article(id,user_id,sort_id,label_id,article_title,article_content) VALUES(3,1,1,1,'Open','body')")
            .execute(&pool).await.unwrap();
        let mut login = Request::builder()
            .method("POST")
            .uri("/api/v2/members/login")
            .header(header::HOST, "127.0.0.1:8081")
            .header(header::ORIGIN, "http://127.0.0.1:8081")
            .header("sec-fetch-site", "same-origin")
            .header(header::CONTENT_TYPE, "application/json")
            .body(Body::from(
                r#"{"account":"member","password":"MemberPassword123"}"#,
            ))
            .unwrap();
        login.extensions_mut().insert(ConnectInfo(
            "127.0.0.1:45678".parse::<SocketAddr>().unwrap(),
        ));
        let response = app.clone().oneshot(login).await.unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let cookie = response
            .headers()
            .get(header::SET_COOKIE)
            .unwrap()
            .to_str()
            .unwrap()
            .split(';')
            .next()
            .unwrap()
            .to_string();
        let session = body_json(response).await;
        let csrf = session["csrf_token"].as_str().unwrap();
        let wrong = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/api/v2/comments")
                    .header(header::HOST, "127.0.0.1:8081")
                    .header(header::ORIGIN, "http://127.0.0.1:8081")
                    .header("sec-fetch-site", "same-origin")
                    .header(header::COOKIE, &cookie)
                    .header("x-csrf-token", "wrong")
                    .header(header::CONTENT_TYPE, "application/json")
                    .body(Body::from(r#"{"article_id":3,"content":"Hello"}"#))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(wrong.status(), StatusCode::FORBIDDEN);
        let correct = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/api/v2/comments")
                    .header(header::HOST, "127.0.0.1:8081")
                    .header(header::ORIGIN, "http://127.0.0.1:8081")
                    .header("sec-fetch-site", "same-origin")
                    .header(header::COOKIE, &cookie)
                    .header("x-csrf-token", csrf)
                    .header(header::CONTENT_TYPE, "application/json")
                    .body(Body::from(r#"{"article_id":3,"content":"Hello"}"#))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(correct.status(), StatusCode::OK);
        assert_eq!(body_json(correct).await["comment_content"], "Hello");
        let saved: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM comment WHERE user_id=2")
            .fetch_one(&pool)
            .await
            .unwrap();
        assert_eq!(saved, 1);
        let family_submission = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/api/v2/family/submissions")
                    .header(header::HOST, "127.0.0.1:8081")
                    .header(header::ORIGIN, "http://127.0.0.1:8081")
                    .header("sec-fetch-site", "same-origin")
                    .header(header::COOKIE, &cookie)
                    .header("x-csrf-token", csrf)
                    .header(header::CONTENT_TYPE, "application/json")
                    .body(Body::from(r#"{"man_name":"A","woman_name":"B","timing":"2024-06-01","family_info":"Our story"}"#))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(family_submission.status(), StatusCode::OK);
        assert_eq!(body_json(family_submission).await["pending"], true);
        let pending: i64 =
            sqlx::query_scalar("SELECT COUNT(*) FROM family WHERE user_id=2 AND status=0")
                .fetch_one(&pool)
                .await
                .unwrap();
        assert_eq!(pending, 1);
        let profile = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("PUT")
                    .uri("/api/v2/members/profile")
                    .header(header::HOST, "127.0.0.1:8081")
                    .header(header::ORIGIN, "http://127.0.0.1:8081")
                    .header("sec-fetch-site", "same-origin")
                    .header(header::COOKIE, &cookie)
                    .header("x-csrf-token", csrf)
                    .header(header::CONTENT_TYPE, "application/json")
                    .body(Body::from(
                        r#"{"username":"member-renamed","avatar":null,"introduction":"hello"}"#,
                    ))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(profile.status(), StatusCode::OK);
        assert_eq!(body_json(profile).await["username"], "member-renamed");
        let password = app.clone().oneshot(Request::builder()
            .method("POST").uri("/api/v2/members/password")
            .header(header::HOST,"127.0.0.1:8081")
            .header(header::ORIGIN,"http://127.0.0.1:8081")
            .header("sec-fetch-site","same-origin")
            .header(header::COOKIE,&cookie)
            .header("x-csrf-token",csrf)
            .header(header::CONTENT_TYPE,"application/json")
            .body(Body::from(r#"{"current_password":"MemberPassword123","new_password":"UpdatedMemberPassword123"}"#)).unwrap()).await.unwrap();
        assert_eq!(password.status(), StatusCode::OK);
        let stored: String = sqlx::query_scalar("SELECT password FROM user WHERE id=2")
            .fetch_one(&pool)
            .await
            .unwrap();
        assert!(sarmg_admin_auth::verify_password(
            "UpdatedMemberPassword123",
            &stored
        ));
    }

    #[tokio::test]
    async fn registration_and_public_notes_require_valid_session() {
        let (_dir, app, pool) = setup().await;
        let mut register = Request::builder()
            .method("POST")
            .uri("/api/v2/members/register")
            .header(header::HOST, "127.0.0.1:8081")
            .header(header::ORIGIN, "http://127.0.0.1:8081")
            .header("sec-fetch-site", "same-origin")
            .header(header::CONTENT_TYPE, "application/json")
            .body(Body::from(
                r#"{"username":"reader","password":"LongReaderPassword123"}"#,
            ))
            .unwrap();
        register.extensions_mut().insert(ConnectInfo(
            "127.0.0.1:45679".parse::<SocketAddr>().unwrap(),
        ));
        let response = app.clone().oneshot(register).await.unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let cookie = response.headers()[header::SET_COOKIE]
            .to_str()
            .unwrap()
            .split(';')
            .next()
            .unwrap()
            .to_owned();
        let session = body_json(response).await;
        let csrf = session["csrf_token"].as_str().unwrap();
        let create = app
            .clone()
            .oneshot(
                Request::builder()
                    .method("POST")
                    .uri("/api/v2/notes")
                    .header(header::HOST, "127.0.0.1:8081")
                    .header(header::ORIGIN, "http://127.0.0.1:8081")
                    .header("sec-fetch-site", "same-origin")
                    .header(header::COOKIE, cookie)
                    .header("x-csrf-token", csrf)
                    .header(header::CONTENT_TYPE, "application/json")
                    .body(Body::from(r#"{"content":"first note"}"#))
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(create.status(), StatusCode::OK);
        let saved: i64 = sqlx::query_scalar(
            "SELECT COUNT(*) FROM wei_yan WHERE content='first note' AND is_public=1",
        )
        .fetch_one(&pool)
        .await
        .unwrap();
        assert_eq!(saved, 1);
        let unknown = app
            .oneshot(
                Request::builder()
                    .uri("/api/v2/missing")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(unknown.status(), StatusCode::NOT_FOUND);
    }

    #[tokio::test]
    async fn search_ranks_titles_and_hides_protected_body_matches() {
        let (_dir, app, pool) = setup().await;
        sqlx::query("INSERT INTO article(id,user_id,sort_id,label_id,article_title,article_content) VALUES(1,1,1,1,'春日记','普通正文'),(2,1,1,1,'另一篇','正文提到春日记')")
            .execute(&pool).await.unwrap();
        let hash = sarmg_admin_auth::hash_password("ProtectedPassphrase123").unwrap();
        sqlx::query("INSERT INTO article(id,user_id,sort_id,label_id,article_title,article_content,view_status,password) VALUES(3,1,1,1,'加密文章','春日记藏在正文',0,?)")
            .bind(hash).execute(&pool).await.unwrap();
        let search = app
            .clone()
            .oneshot(
                Request::builder()
                    .uri("/api/v2/articles?search=%E6%98%A5%E6%97%A5%E8%AE%B0")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(search.status(), StatusCode::OK);
        let result = body_json(search).await;
        assert_eq!(result["total"], 2);
        assert_eq!(result["items"][0]["id"], 1);
        assert_eq!(result["items"][1]["id"], 2);
        assert!(
            result["items"][0]["excerpt"]
                .as_str()
                .unwrap()
                .contains("普通正文")
        );
        assert!(
            result["items"][1]["search_snippet"]
                .as_str()
                .unwrap()
                .contains("春日记")
        );
        let short = app
            .clone()
            .oneshot(
                Request::builder()
                    .uri("/api/v2/articles?search=%E6%98%A5%E6%97%A5")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(body_json(short).await["total"], 2);
        sqlx::query("UPDATE article SET article_content='正文已修改' WHERE id=2")
            .execute(&pool)
            .await
            .unwrap();
        let after = app
            .oneshot(
                Request::builder()
                    .uri("/api/v2/articles?search=%E6%98%A5%E6%97%A5%E8%AE%B0")
                    .body(Body::empty())
                    .unwrap(),
            )
            .await
            .unwrap();
        assert_eq!(body_json(after).await["total"], 1);
    }
}
