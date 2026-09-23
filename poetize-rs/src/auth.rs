use std::{
    net::SocketAddr,
    sync::LazyLock,
    time::{SystemTime, UNIX_EPOCH},
};

use axum::{
    Json, Router,
    extract::{ConnectInfo, Extension, Request, State},
    http::{HeaderMap, HeaderValue, Method, StatusCode, header},
    middleware::Next,
    response::{IntoResponse, Response},
    routing::{get, post},
};
use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use sqlx::{Row, SqlitePool};

use crate::{ApiResult, AppError, AppState, db_error, invalid};

const COOKIE_NAME: &str = "poetize_member";
const SESSION_SECONDS: i64 = 60 * 60 * 24;
const FAILURE_WINDOW_SECONDS: i64 = 15 * 60;
static DUMMY_HASH: LazyLock<String> = LazyLock::new(|| {
    sarmg_admin_auth::hash_password("InvalidMemberPassword123").expect("valid dummy password")
});

#[derive(Clone, Copy)]
pub struct Member {
    pub id: i64,
}

#[derive(Serialize)]
struct MemberSession {
    id: i64,
    username: String,
    avatar: Option<String>,
    csrf_token: String,
}

#[derive(Deserialize)]
struct LoginInput {
    account: String,
    password: String,
}

#[derive(Deserialize)]
struct RegisterInput {
    username: String,
    password: String,
}

pub fn public_routes() -> Router<AppState> {
    Router::new()
        .route("/api/v2/members/login", post(login))
        .route("/api/v2/members/register", post(register))
        .route("/api/v2/members/session", get(session))
}

pub fn protected_routes() -> Router<AppState> {
    Router::new()
        .route("/api/v2/members/logout", post(logout))
        .route("/api/v2/members/profile", get(profile).put(update_profile))
        .route("/api/v2/members/password", post(change_password))
}

#[derive(Serialize, sqlx::FromRow)]
struct MemberProfile {
    id: i64,
    username: String,
    email: Option<String>,
    avatar: Option<String>,
    introduction: Option<String>,
}
async fn profile(
    State(state): State<AppState>,
    Extension(member): Extension<Member>,
) -> ApiResult<MemberProfile> {
    Ok(Json(
        sqlx::query_as("SELECT id,username,email,avatar,introduction FROM user WHERE id=?")
            .bind(member.id)
            .fetch_one(&state.pool)
            .await
            .map_err(db_error)?,
    ))
}
#[derive(Deserialize)]
struct ProfileInput {
    username: String,
    avatar: Option<String>,
    introduction: Option<String>,
}
async fn update_profile(
    State(state): State<AppState>,
    Extension(member): Extension<Member>,
    Json(input): Json<ProfileInput>,
) -> ApiResult<MemberProfile> {
    let username = input.username.trim();
    if !(3..=32).contains(&username.chars().count())
        || !username
            .chars()
            .all(|c| c.is_alphanumeric() || c == '_' || c == '-')
        || input.avatar.as_deref().is_some_and(|v| {
            v.len() > 2048
                || !(v.starts_with("https://")
                    || v.starts_with("http://")
                    || v.starts_with("/media/"))
        })
        || input
            .introduction
            .as_deref()
            .is_some_and(|v| v.chars().count() > 512)
    {
        return Err(invalid("资料内容无效"));
    }
    let result=sqlx::query("UPDATE user SET username=?,avatar=?,introduction=?,update_time=CURRENT_TIMESTAMP WHERE id=?")
        .bind(username).bind(input.avatar).bind(input.introduction).bind(member.id).execute(&state.pool).await;
    match result {
        Ok(_) => profile(State(state), Extension(member)).await,
        Err(sqlx::Error::Database(error)) if error.is_unique_violation() => {
            Err(AppError(StatusCode::CONFLICT, "用户名已被使用"))
        }
        Err(error) => Err(db_error(error)),
    }
}
#[derive(Deserialize)]
struct PasswordInput {
    current_password: String,
    new_password: String,
}
async fn change_password(
    State(state): State<AppState>,
    Extension(member): Extension<Member>,
    headers: HeaderMap,
    Json(input): Json<PasswordInput>,
) -> ApiResult<serde_json::Value> {
    sarmg_admin_auth::validate_password(&input.new_password)
        .map_err(|_| invalid("新密码至少需要 12 字节"))?;
    let stored: Option<String> = sqlx::query_scalar("SELECT password FROM user WHERE id=?")
        .bind(member.id)
        .fetch_one(&state.pool)
        .await
        .map_err(db_error)?;
    if !stored
        .as_deref()
        .is_some_and(|hash| matches_password(&input.current_password, hash))
    {
        return Err(AppError(StatusCode::FORBIDDEN, "当前密码错误"));
    }
    let hash =
        sarmg_admin_auth::hash_password(&input.new_password).map_err(|_| invalid("新密码无效"))?;
    sqlx::query("UPDATE user SET password=?,update_time=CURRENT_TIMESTAMP WHERE id=?")
        .bind(hash)
        .bind(member.id)
        .execute(&state.pool)
        .await
        .map_err(db_error)?;
    let token = session_token(&headers).ok_or(AppError(StatusCode::UNAUTHORIZED, "请先登录"))?;
    sqlx::query("DELETE FROM member_sessions WHERE user_id=? AND token_hash<>?")
        .bind(member.id)
        .bind(sarmg_admin_auth::token_hash(token).to_vec())
        .execute(&state.pool)
        .await
        .map_err(db_error)?;
    Ok(Json(serde_json::json!({"changed":true})))
}

fn now_seconds() -> i64 {
    i64::try_from(
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .expect("clock before epoch")
            .as_secs(),
    )
    .expect("clock out of range")
}

fn require_same_origin(
    headers: &HeaderMap,
    mode: sarmg_admin_auth::AdministratorOriginMode,
) -> Result<(), AppError> {
    let values = |name: &str| {
        headers
            .get_all(name)
            .iter()
            .map(|v| v.as_bytes())
            .collect::<Vec<_>>()
    };
    sarmg_admin_auth::require_administrator_same_origin(
        mode,
        &values("origin"),
        &values("host"),
        &values("sec-fetch-site"),
    )
    .map(|_| ())
    .map_err(|_| AppError(StatusCode::FORBIDDEN, "请求来源不可信"))
}

fn session_token(headers: &HeaderMap) -> Option<&str> {
    let mut cookies = headers.get_all(header::COOKIE).iter();
    let raw = cookies.next()?.to_str().ok()?;
    if cookies.next().is_some() {
        return None;
    }
    let token = sarmg_admin_auth::parse_cookie_value(raw, COOKIE_NAME)?;
    sarmg_admin_auth::is_token_shape(token).then_some(token)
}

fn csrf_for_token(token: &str) -> String {
    let mut digest = Sha256::new();
    digest.update(b"poetize/member-csrf/v1\0");
    digest.update(token.as_bytes());
    URL_SAFE_NO_PAD.encode(digest.finalize())
}

fn cookie(token: &str, secure: bool) -> Result<HeaderValue, AppError> {
    let security = if secure { "; Secure" } else { "" };
    HeaderValue::from_str(&format!(
        "{COOKIE_NAME}={token}; Path=/; HttpOnly; SameSite=Lax; Max-Age={SESSION_SECONDS}{security}"
    ))
    .map_err(|_| AppError(StatusCode::INTERNAL_SERVER_ERROR, "会话设置失败"))
}

async fn authenticated(
    pool: &SqlitePool,
    headers: &HeaderMap,
) -> Result<(MemberSession, Vec<u8>), AppError> {
    let token = session_token(headers).ok_or(AppError(StatusCode::UNAUTHORIZED, "请先登录"))?;
    let row = sqlx::query("SELECT u.id,u.username,u.avatar,s.csrf_hash FROM member_sessions s JOIN user u ON u.id=s.user_id WHERE s.token_hash=? AND s.expires_at>? AND u.user_status=1 AND u.deleted=0")
        .bind(sarmg_admin_auth::token_hash(token).to_vec()).bind(now_seconds()).fetch_optional(pool).await.map_err(db_error)?
        .ok_or(AppError(StatusCode::UNAUTHORIZED, "登录已过期"))?;
    Ok((
        MemberSession {
            id: row.try_get("id").map_err(db_error)?,
            username: row.try_get("username").map_err(db_error)?,
            avatar: row.try_get("avatar").map_err(db_error)?,
            csrf_token: csrf_for_token(token),
        },
        row.try_get("csrf_hash").map_err(db_error)?,
    ))
}

pub async fn websocket_member(state: &AppState, headers: &HeaderMap) -> Result<Member, AppError> {
    require_same_origin(headers, state.origin)?;
    let (session, _) = authenticated(&state.pool, headers).await?;
    Ok(Member { id: session.id })
}

pub async fn member_gate(
    State(state): State<AppState>,
    mut request: Request,
    next: Next,
) -> Response {
    let (session, csrf_hash) = match authenticated(&state.pool, request.headers()).await {
        Ok(value) => value,
        Err(error) => return error.into_response(),
    };
    if !matches!(
        *request.method(),
        Method::GET | Method::HEAD | Method::OPTIONS
    ) {
        if let Err(error) = require_same_origin(request.headers(), state.origin) {
            return error.into_response();
        }
        let csrf_headers = request
            .headers()
            .get_all("x-csrf-token")
            .iter()
            .map(|v| v.as_bytes())
            .collect::<Vec<_>>();
        if sarmg_admin_auth::require_csrf_token_matches_hash(&csrf_headers, &csrf_hash).is_err() {
            return AppError(StatusCode::FORBIDDEN, "CSRF 校验失败").into_response();
        }
    }
    request.extensions_mut().insert(Member { id: session.id });
    next.run(request).await
}

async fn session(State(state): State<AppState>, headers: HeaderMap) -> ApiResult<MemberSession> {
    let (session, _) = authenticated(&state.pool, &headers).await?;
    Ok(Json(session))
}

async fn logout(State(state): State<AppState>, headers: HeaderMap) -> Result<Response, AppError> {
    let token = session_token(&headers).ok_or(AppError(StatusCode::UNAUTHORIZED, "请先登录"))?;
    sqlx::query("DELETE FROM member_sessions WHERE token_hash=?")
        .bind(sarmg_admin_auth::token_hash(token).to_vec())
        .execute(&state.pool)
        .await
        .map_err(db_error)?;
    let mut response = Json(serde_json::json!({"logged_out":true})).into_response();
    let clear = format!(
        "{COOKIE_NAME}=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0{}",
        if state.origin == sarmg_admin_auth::AdministratorOriginMode::ProductionHttps {
            "; Secure"
        } else {
            ""
        }
    );
    response.headers_mut().insert(
        header::SET_COOKIE,
        HeaderValue::from_str(&clear).map_err(|_| invalid("Cookie 无效"))?,
    );
    Ok(response)
}

pub(crate) async fn record_attempt(
    pool: &SqlitePool,
    key: &[u8],
    limit: i64,
) -> Result<(), AppError> {
    let now = now_seconds();
    let count: i64 = sqlx::query_scalar("INSERT INTO member_login_failures(failure_key,failures,expires_at) VALUES(?,1,?) ON CONFLICT(failure_key) DO UPDATE SET failures=CASE WHEN expires_at<=? THEN 1 ELSE failures+1 END,expires_at=CASE WHEN expires_at<=? THEN excluded.expires_at ELSE expires_at END RETURNING failures")
        .bind(key).bind(now+FAILURE_WINDOW_SECONDS).bind(now).bind(now).fetch_one(pool).await.map_err(db_error)?;
    if count > limit {
        return Err(AppError(
            StatusCode::TOO_MANY_REQUESTS,
            "登录尝试过多，请稍后再试",
        ));
    }
    Ok(())
}

pub(crate) fn attempt_key(kind: &str, ip: &str, account: &str) -> Vec<u8> {
    let mut hash = Sha256::new();
    hash.update(kind.as_bytes());
    hash.update([0]);
    hash.update(ip.as_bytes());
    hash.update([0]);
    hash.update(account.as_bytes());
    hash.finalize().to_vec()
}

fn matches_password(password: &str, stored: &str) -> bool {
    sarmg_admin_auth::verify_password(password, stored)
}

async fn login(
    State(state): State<AppState>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    Json(input): Json<LoginInput>,
) -> Result<Response, AppError> {
    require_same_origin(&headers, state.origin)?;
    let account = input.account.trim();
    if account.is_empty()
        || account.len() > 128
        || input.password.is_empty()
        || input.password.len() > 1024
    {
        return Err(invalid("账号或密码无效"));
    }
    let ip = peer.ip().to_string();
    let account_key = attempt_key("account", &ip, &account.to_lowercase());
    let ip_key = attempt_key("ip", &ip, "");
    record_attempt(&state.pool, &account_key, 10).await?;
    record_attempt(&state.pool, &ip_key, 50).await?;
    let user = sqlx::query("SELECT id,username,avatar,password,user_status FROM user WHERE deleted=0 AND (username=? OR email=? OR phone_number=?) LIMIT 1")
        .bind(account).bind(account).bind(account).fetch_optional(&state.pool).await.map_err(db_error)?;
    let mut valid = false;
    let mut id = 0;
    let mut username = String::new();
    let mut avatar = None;
    if let Some(row) = user {
        id = row.try_get("id").map_err(db_error)?;
        username = row.try_get("username").map_err(db_error)?;
        avatar = row.try_get("avatar").map_err(db_error)?;
        let password_hash = row
            .try_get::<Option<String>, _>("password")
            .map_err(db_error)?
            .unwrap_or_default();
        valid = row.try_get::<i64, _>("user_status").map_err(db_error)? == 1
            && matches_password(&input.password, &password_hash);
    } else {
        let _ = sarmg_admin_auth::verify_password(&input.password, &DUMMY_HASH);
    }
    if !valid {
        return Err(AppError(StatusCode::UNAUTHORIZED, "账号或密码错误"));
    }
    sqlx::query("DELETE FROM member_login_failures WHERE failure_key=? OR failure_key=?")
        .bind(&account_key)
        .bind(&ip_key)
        .execute(&state.pool)
        .await
        .map_err(db_error)?;
    start_session(&state, id, username, avatar).await
}

async fn register(
    State(state): State<AppState>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    headers: HeaderMap,
    Json(input): Json<RegisterInput>,
) -> Result<Response, AppError> {
    require_same_origin(&headers, state.origin)?;
    let username = input.username.trim();
    if !(3..=32).contains(&username.chars().count())
        || !username
            .chars()
            .all(|c| c.is_alphanumeric() || c == '_' || c == '-')
        || username == "site-author"
    {
        return Err(invalid("用户名需为 3 到 32 个字母、数字、下划线或连字符"));
    }
    sarmg_admin_auth::validate_password(&input.password)
        .map_err(|_| invalid("密码至少需要 12 字节"))?;
    let key = attempt_key("register", &peer.ip().to_string(), "");
    record_attempt(&state.pool, &key, 10).await?;
    let hash = sarmg_admin_auth::hash_password(&input.password).map_err(|_| invalid("密码无效"))?;
    let result =
        sqlx::query("INSERT INTO user(username,password,user_type,user_status) VALUES(?,?,2,1)")
            .bind(username)
            .bind(hash)
            .execute(&state.pool)
            .await;
    let id = match result {
        Ok(row) => row.last_insert_rowid(),
        Err(sqlx::Error::Database(error)) if error.is_unique_violation() => {
            return Err(AppError(StatusCode::CONFLICT, "用户名已被使用"));
        }
        Err(error) => return Err(db_error(error)),
    };
    start_session(&state, id, username.to_owned(), None).await
}

async fn start_session(
    state: &AppState,
    id: i64,
    username: String,
    avatar: Option<String>,
) -> Result<Response, AppError> {
    let token = sarmg_admin_auth::random_token()
        .map_err(|_| AppError(StatusCode::INTERNAL_SERVER_ERROR, "会话生成失败"))?;
    let csrf = csrf_for_token(&token);
    sqlx::query("INSERT INTO member_sessions(token_hash,csrf_hash,user_id,created_at,expires_at) VALUES(?,?,?,?,?)")
        .bind(sarmg_admin_auth::token_hash(&token).to_vec()).bind(sarmg_admin_auth::token_hash(&csrf).to_vec())
        .bind(id).bind(now_seconds()).bind(now_seconds()+SESSION_SECONDS).execute(&state.pool).await.map_err(db_error)?;
    let session = MemberSession {
        id,
        username,
        avatar,
        csrf_token: csrf,
    };
    let mut response = Json(session).into_response();
    response.headers_mut().insert(
        header::SET_COOKIE,
        cookie(
            &token,
            state.origin == sarmg_admin_auth::AdministratorOriginMode::ProductionHttps,
        )?,
    );
    response
        .headers_mut()
        .insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
    Ok(response)
}
