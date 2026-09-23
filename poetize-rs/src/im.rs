use axum::{
    Json, Router,
    extract::{
        Extension, Path, Query, State,
        ws::{Message, WebSocket, WebSocketUpgrade},
    },
    http::{HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    routing::{get, post},
};
use futures_util::{SinkExt, StreamExt};
use serde::{Deserialize, Serialize};
use sqlx::SqlitePool;

use crate::{
    ApiResult, AppError, AppState,
    auth::{self, Member},
    db_error, invalid,
};

#[derive(Clone)]
pub struct OutboundEvent {
    recipients: Vec<i64>,
    wire: String,
}

#[derive(Deserialize)]
struct IncomingMessage {
    kind: String,
    to_id: Option<i64>,
    group_id: Option<i64>,
    content: String,
}

#[derive(Serialize, sqlx::FromRow)]
struct DirectMessage {
    id: i64,
    from_id: i64,
    to_id: i64,
    content: String,
    message_status: Option<i64>,
    create_time: Option<String>,
}

#[derive(Serialize, sqlx::FromRow)]
struct GroupMessage {
    id: i64,
    group_id: i64,
    from_id: i64,
    content: String,
    create_time: Option<String>,
}

#[derive(Serialize, sqlx::FromRow)]
struct Friend {
    id: i64,
    username: String,
    avatar: Option<String>,
    remark: Option<String>,
}

#[derive(Serialize, sqlx::FromRow)]
struct Group {
    id: i64,
    group_name: String,
    avatar: Option<String>,
    introduction: Option<String>,
    notice: Option<String>,
    master_user_id: i64,
    in_type: i64,
    group_type: i64,
}

#[derive(Deserialize)]
struct GroupInput {
    name: String,
    introduction: Option<String>,
    in_type: Option<i64>,
}

pub fn member_routes() -> Router<AppState> {
    Router::new()
        .route("/api/v2/im/friends", get(list_friends))
        .route("/api/v2/im/friends/requests", get(friend_requests))
        .route("/api/v2/im/users", get(search_users))
        .route("/api/v2/im/friends/{id}/request", post(request_friend))
        .route("/api/v2/im/friends/{id}/accept", post(accept_friend))
        .route("/api/v2/im/friends/{id}/decline", post(decline_friend))
        .route(
            "/api/v2/im/friends/{id}",
            axum::routing::delete(remove_friend),
        )
        .route(
            "/api/v2/im/friends/{id}/remark",
            axum::routing::put(update_friend_remark),
        )
        .route("/api/v2/im/direct/{id}", get(direct_history))
        .route("/api/v2/im/groups", get(list_groups).post(create_group))
        .route("/api/v2/im/groups/discover", get(discover_groups))
        .route("/api/v2/im/groups/{id}/join", post(join_group))
        .route("/api/v2/im/groups/{id}/leave", post(leave_group))
        .route("/api/v2/im/groups/{id}/requests", get(group_requests))
        .route(
            "/api/v2/im/groups/{id}/members/{user_id}/approve",
            post(approve_group_member),
        )
        .route("/api/v2/im/groups/{id}/messages", get(group_history))
}

pub fn websocket_routes() -> Router<AppState> {
    Router::new().route("/socket", get(websocket))
}

async fn websocket(
    State(state): State<AppState>,
    headers: HeaderMap,
    upgrade: WebSocketUpgrade,
) -> Result<Response, AppError> {
    let member = auth::websocket_member(&state, &headers).await?;
    Ok(upgrade
        .on_upgrade(move |socket| websocket_loop(socket, state, member))
        .into_response())
}

async fn websocket_loop(socket: WebSocket, state: AppState, member: Member) {
    let (mut sender, mut receiver) = socket.split();
    let mut events = state.events.subscribe();
    loop {
        tokio::select! {
            incoming=receiver.next()=>{
                let text=match incoming {
                    Some(Ok(Message::Text(text)))=>text,
                    Some(Ok(Message::Ping(_) | Message::Pong(_) | Message::Binary(_)))=>continue,
                    _=>break,
                };
                if text.len()>8192 {continue;}
                let Ok(message)=serde_json::from_str::<IncomingMessage>(&text) else {continue};
                let outcome=send_message(&state,member,message).await;
                let reply=match outcome {
                    Ok(id)=>serde_json::json!({"kind":"ack","message_id":id}).to_string(),
                    Err(error)=>serde_json::json!({"kind":"error","error":error.1}).to_string(),
                };
                if sender.send(Message::Text(reply.into())).await.is_err(){break;}
            }
            event=events.recv()=>{
                match event {
                    Ok(event) if event.recipients.contains(&member.id)=>{
                        if sender.send(Message::Text(event.wire.into())).await.is_err(){break;}
                    }
                    Ok(_)=>{},
                    Err(tokio::sync::broadcast::error::RecvError::Lagged(_))=>{
                        if sender.send(Message::Text(r#"{"kind":"resync"}"#.into())).await.is_err(){break;}
                    }
                    Err(tokio::sync::broadcast::error::RecvError::Closed)=>break,
                }
            }
        }
    }
}

async fn friends_with(pool: &SqlitePool, user: i64, other: i64) -> Result<bool, AppError> {
    let count: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM im_chat_user_friend WHERE user_id=? AND friend_id=? AND friend_status=1")
        .bind(user).bind(other).fetch_one(pool).await.map_err(db_error)?;
    Ok(count > 0)
}

async fn member_of(pool: &SqlitePool, group: i64, user: i64) -> Result<bool, AppError> {
    let count: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM im_chat_group_user WHERE group_id=? AND user_id=? AND user_status=1",
    )
    .bind(group)
    .bind(user)
    .fetch_one(pool)
    .await
    .map_err(db_error)?;
    Ok(count > 0)
}

async fn send_message(
    state: &AppState,
    member: Member,
    input: IncomingMessage,
) -> Result<i64, AppError> {
    if input.content.trim().is_empty()
        || input.content.chars().count() > 1024
        || input.content.contains('<')
        || input.content.contains('>')
    {
        return Err(invalid("消息内容无效"));
    }
    action_limit(&state.pool, member.id, "im_message", 60, 60).await?;
    if input.kind == "direct" {
        let to = input.to_id.ok_or_else(|| invalid("缺少接收人"))?;
        if to == member.id || !friends_with(&state.pool, member.id, to).await? {
            return Err(AppError(StatusCode::FORBIDDEN, "只能给好友发送私信"));
        }
        let recipient: Option<i64> =
            sqlx::query_scalar("SELECT id FROM user WHERE id=? AND deleted=0 AND user_status=1")
                .bind(to)
                .fetch_optional(&state.pool)
                .await
                .map_err(db_error)?;
        if recipient.is_none() {
            return Err(invalid("接收人不存在"));
        }
        let id=sqlx::query("INSERT INTO im_chat_user_message(from_id,to_id,content,message_status) VALUES(?,?,?,0)")
            .bind(member.id).bind(to).bind(&input.content).execute(&state.pool).await.map_err(db_error)?.last_insert_rowid();
        let wire=serde_json::json!({"kind":"direct","id":id,"from_id":member.id,"to_id":to,"content":input.content}).to_string();
        let _ = state.events.send(OutboundEvent {
            recipients: vec![member.id, to],
            wire,
        });
        return Ok(id);
    }
    if input.kind == "group" {
        let group = input.group_id.ok_or_else(|| invalid("缺少群组"))?;
        if !member_of(&state.pool, group, member.id).await? {
            return Err(AppError(StatusCode::FORBIDDEN, "你不在群组中或已被禁言"));
        }
        let id = sqlx::query(
            "INSERT INTO im_chat_user_group_message(group_id,from_id,content) VALUES(?,?,?)",
        )
        .bind(group)
        .bind(member.id)
        .bind(&input.content)
        .execute(&state.pool)
        .await
        .map_err(db_error)?
        .last_insert_rowid();
        let users: Vec<i64> = sqlx::query_scalar(
            "SELECT user_id FROM im_chat_group_user WHERE group_id=? AND user_status=1",
        )
        .bind(group)
        .fetch_all(&state.pool)
        .await
        .map_err(db_error)?;
        let wire=serde_json::json!({"kind":"group","id":id,"group_id":group,"from_id":member.id,"content":input.content}).to_string();
        let _ = state.events.send(OutboundEvent {
            recipients: users,
            wire,
        });
        return Ok(id);
    }
    Err(invalid("未知消息类型"))
}

async fn action_limit(
    pool: &SqlitePool,
    user_id: i64,
    kind: &str,
    limit: i64,
    window_seconds: i64,
) -> Result<(), AppError> {
    let now = (crate::now_micros()
        .map_err(|_| AppError(StatusCode::INTERNAL_SERVER_ERROR, "时钟不可用"))?
        / 1_000_000) as i64;
    let count:i64=sqlx::query_scalar("INSERT INTO member_write_limits(user_id,kind,window_start,count) VALUES(?,?,?,1) ON CONFLICT(user_id,kind) DO UPDATE SET count=CASE WHEN window_start<? THEN 1 ELSE count+1 END,window_start=CASE WHEN window_start<? THEN excluded.window_start ELSE window_start END RETURNING count")
        .bind(user_id).bind(kind).bind(now).bind(now-window_seconds).bind(now-window_seconds)
        .fetch_one(pool).await.map_err(db_error)?;
    if count > limit {
        return Err(AppError(
            StatusCode::TOO_MANY_REQUESTS,
            "操作太频繁，请稍后再试",
        ));
    }
    Ok(())
}

async fn list_friends(
    State(state): State<AppState>,
    Extension(member): Extension<Member>,
) -> ApiResult<Vec<Friend>> {
    let rows=sqlx::query_as::<_,Friend>("SELECT u.id,u.username,u.avatar,f.remark FROM im_chat_user_friend f JOIN user u ON u.id=f.friend_id WHERE f.user_id=? AND f.friend_status=1 AND u.deleted=0 AND u.user_status=1 ORDER BY u.username LIMIT 500")
        .bind(member.id).fetch_all(&state.pool).await.map_err(db_error)?;
    Ok(Json(rows))
}

#[derive(Serialize, sqlx::FromRow)]
struct PendingUser {
    id: i64,
    username: String,
    avatar: Option<String>,
}
async fn friend_requests(
    State(state): State<AppState>,
    Extension(member): Extension<Member>,
) -> ApiResult<Vec<PendingUser>> {
    Ok(Json(sqlx::query_as::<_,PendingUser>("SELECT u.id,u.username,u.avatar FROM im_chat_user_friend f JOIN user u ON u.id=f.user_id WHERE f.friend_id=? AND f.friend_status=0 AND u.deleted=0 AND u.user_status=1 ORDER BY f.id DESC LIMIT 100")
        .bind(member.id).fetch_all(&state.pool).await.map_err(db_error)?))
}

#[derive(Deserialize)]
struct SearchQuery {
    search: String,
}
fn search_pattern(search: &str) -> Result<String, AppError> {
    let term = search.trim();
    if term.chars().count() < 2 || term.chars().count() > 64 {
        return Err(invalid("搜索词长度需为 2 到 64 个字"));
    }
    Ok(format!(
        "%{}%",
        term.replace('\\', "\\\\")
            .replace('%', "\\%")
            .replace('_', "\\_")
    ))
}
async fn search_users(
    State(state): State<AppState>,
    Extension(member): Extension<Member>,
    Query(query): Query<SearchQuery>,
) -> ApiResult<Vec<PendingUser>> {
    let pattern = search_pattern(&query.search)?;
    Ok(Json(sqlx::query_as::<_,PendingUser>("SELECT id,username,avatar FROM user WHERE username LIKE ? ESCAPE '\\' AND id<>? AND deleted=0 AND user_status=1 ORDER BY username LIMIT 30")
        .bind(pattern).bind(member.id).fetch_all(&state.pool).await.map_err(db_error)?))
}

async fn discover_groups(
    State(state): State<AppState>,
    Query(query): Query<SearchQuery>,
) -> ApiResult<Vec<Group>> {
    let pattern = search_pattern(&query.search)?;
    Ok(Json(sqlx::query_as::<_,Group>("SELECT id,group_name,avatar,introduction,notice,master_user_id,in_type,group_type FROM im_chat_group WHERE group_name LIKE ? ESCAPE '\\' ORDER BY id DESC LIMIT 50")
        .bind(pattern).fetch_all(&state.pool).await.map_err(db_error)?))
}

async fn request_friend(
    State(state): State<AppState>,
    Extension(member): Extension<Member>,
    Path(id): Path<i64>,
) -> ApiResult<serde_json::Value> {
    if id == member.id || id <= 0 {
        return Err(invalid("好友 ID 无效"));
    }
    let target: Option<i64> =
        sqlx::query_scalar("SELECT id FROM user WHERE id=? AND deleted=0 AND user_status=1")
            .bind(id)
            .fetch_optional(&state.pool)
            .await
            .map_err(db_error)?;
    if target.is_none() {
        return Err(invalid("用户不存在"));
    }
    action_limit(&state.pool, member.id, "friend_request", 20, 86_400).await?;
    sqlx::query("INSERT INTO im_chat_user_friend(user_id,friend_id,friend_status) VALUES(?,?,0) ON CONFLICT(user_id,friend_id) DO NOTHING")
        .bind(member.id).bind(id).execute(&state.pool).await.map_err(db_error)?;
    Ok(Json(serde_json::json!({"requested":true})))
}

async fn accept_friend(
    State(state): State<AppState>,
    Extension(member): Extension<Member>,
    Path(id): Path<i64>,
) -> ApiResult<serde_json::Value> {
    let mut tx = state.pool.begin().await.map_err(db_error)?;
    let changed=sqlx::query("UPDATE im_chat_user_friend SET friend_status=1 WHERE user_id=? AND friend_id=? AND friend_status=0")
        .bind(id).bind(member.id).execute(&mut *tx).await.map_err(db_error)?;
    if changed.rows_affected() == 0 {
        return Err(invalid("没有待处理的好友申请"));
    }
    sqlx::query("INSERT INTO im_chat_user_friend(user_id,friend_id,friend_status) VALUES(?,?,1) ON CONFLICT(user_id,friend_id) DO UPDATE SET friend_status=1")
        .bind(member.id).bind(id).execute(&mut *tx).await.map_err(db_error)?;
    tx.commit().await.map_err(db_error)?;
    Ok(Json(serde_json::json!({"accepted":true})))
}
async fn decline_friend(
    State(state): State<AppState>,
    Extension(member): Extension<Member>,
    Path(id): Path<i64>,
) -> ApiResult<serde_json::Value> {
    let changed = sqlx::query(
        "DELETE FROM im_chat_user_friend WHERE user_id=? AND friend_id=? AND friend_status=0",
    )
    .bind(id)
    .bind(member.id)
    .execute(&state.pool)
    .await
    .map_err(db_error)?;
    if changed.rows_affected() == 0 {
        return Err(invalid("没有待处理的好友申请"));
    }
    Ok(Json(serde_json::json!({"declined":true})))
}
async fn remove_friend(
    State(state): State<AppState>,
    Extension(member): Extension<Member>,
    Path(id): Path<i64>,
) -> ApiResult<serde_json::Value> {
    let changed=sqlx::query("DELETE FROM im_chat_user_friend WHERE ((user_id=? AND friend_id=?) OR (user_id=? AND friend_id=?)) AND friend_status=1")
        .bind(member.id).bind(id).bind(id).bind(member.id).execute(&state.pool).await.map_err(db_error)?;
    if changed.rows_affected() == 0 {
        return Err(invalid("好友不存在"));
    }
    Ok(Json(serde_json::json!({"removed":true})))
}
#[derive(Deserialize)]
struct RemarkInput {
    remark: Option<String>,
}
async fn update_friend_remark(
    State(state): State<AppState>,
    Extension(member): Extension<Member>,
    Path(id): Path<i64>,
    Json(input): Json<RemarkInput>,
) -> ApiResult<serde_json::Value> {
    if input
        .remark
        .as_deref()
        .is_some_and(|value| value.chars().count() > 32)
    {
        return Err(invalid("备注不能超过 32 字"));
    }
    let changed=sqlx::query("UPDATE im_chat_user_friend SET remark=? WHERE user_id=? AND friend_id=? AND friend_status=1")
        .bind(input.remark.as_deref().map(str::trim).filter(|v|!v.is_empty())).bind(member.id).bind(id)
        .execute(&state.pool).await.map_err(db_error)?;
    if changed.rows_affected() == 0 {
        return Err(invalid("好友不存在"));
    }
    Ok(Json(serde_json::json!({"updated":true})))
}

async fn direct_history(
    State(state): State<AppState>,
    Extension(member): Extension<Member>,
    Path(id): Path<i64>,
) -> ApiResult<Vec<DirectMessage>> {
    if !friends_with(&state.pool, member.id, id).await? {
        return Err(AppError(StatusCode::FORBIDDEN, "只能查看好友私信"));
    }
    let mut rows=sqlx::query_as::<_,DirectMessage>("SELECT id,from_id,to_id,content,message_status,create_time FROM im_chat_user_message WHERE (from_id=? AND to_id=?) OR (from_id=? AND to_id=?) ORDER BY id DESC LIMIT 100")
        .bind(member.id).bind(id).bind(id).bind(member.id).fetch_all(&state.pool).await.map_err(db_error)?;
    rows.reverse();
    Ok(Json(rows))
}

async fn list_groups(
    State(state): State<AppState>,
    Extension(member): Extension<Member>,
) -> ApiResult<Vec<Group>> {
    let rows=sqlx::query_as::<_,Group>("SELECT g.id,g.group_name,g.avatar,g.introduction,g.notice,g.master_user_id,g.in_type,g.group_type FROM im_chat_group g JOIN im_chat_group_user u ON u.group_id=g.id WHERE u.user_id=? AND u.user_status=1 ORDER BY g.id DESC LIMIT 500")
        .bind(member.id).fetch_all(&state.pool).await.map_err(db_error)?;
    Ok(Json(rows))
}

async fn create_group(
    State(state): State<AppState>,
    Extension(member): Extension<Member>,
    Json(input): Json<GroupInput>,
) -> ApiResult<Group> {
    if input.name.trim().is_empty()
        || input.name.chars().count() > 32
        || input
            .introduction
            .as_deref()
            .is_some_and(|v| v.chars().count() > 128)
        || !matches!(input.in_type.unwrap_or(0), 0 | 1)
    {
        return Err(invalid("群组信息无效"));
    }
    action_limit(&state.pool, member.id, "group_create", 5, 86_400).await?;
    let mut tx = state.pool.begin().await.map_err(db_error)?;
    let id=sqlx::query("INSERT INTO im_chat_group(group_name,master_user_id,introduction,in_type,group_type) VALUES(?,?,?,?,1)")
        .bind(input.name).bind(member.id).bind(input.introduction).bind(input.in_type.unwrap_or(0)).execute(&mut *tx).await.map_err(db_error)?.last_insert_rowid();
    sqlx::query(
        "INSERT INTO im_chat_group_user(group_id,user_id,admin_flag,user_status) VALUES(?,?,1,1)",
    )
    .bind(id)
    .bind(member.id)
    .execute(&mut *tx)
    .await
    .map_err(db_error)?;
    tx.commit().await.map_err(db_error)?;
    Ok(Json(sqlx::query_as("SELECT id,group_name,avatar,introduction,notice,master_user_id,in_type,group_type FROM im_chat_group WHERE id=?")
        .bind(id).fetch_one(&state.pool).await.map_err(db_error)?))
}

async fn join_group(
    State(state): State<AppState>,
    Extension(member): Extension<Member>,
    Path(id): Path<i64>,
) -> ApiResult<serde_json::Value> {
    let in_type: Option<i64> = sqlx::query_scalar("SELECT in_type FROM im_chat_group WHERE id=?")
        .bind(id)
        .fetch_optional(&state.pool)
        .await
        .map_err(db_error)?;
    let Some(in_type) = in_type else {
        return Err(invalid("群组不存在"));
    };
    let status = if in_type == 0 { 1 } else { 0 };
    sqlx::query("INSERT INTO im_chat_group_user(group_id,user_id,admin_flag,user_status) VALUES(?,?,0,?) ON CONFLICT(group_id,user_id) DO NOTHING")
        .bind(id).bind(member.id).bind(status).execute(&state.pool).await.map_err(db_error)?;
    Ok(Json(serde_json::json!({"status":status})))
}
async fn leave_group(
    State(state): State<AppState>,
    Extension(member): Extension<Member>,
    Path(id): Path<i64>,
) -> ApiResult<serde_json::Value> {
    let owner: Option<i64> =
        sqlx::query_scalar("SELECT master_user_id FROM im_chat_group WHERE id=?")
            .bind(id)
            .fetch_optional(&state.pool)
            .await
            .map_err(db_error)?;
    if owner == Some(member.id) {
        return Err(AppError(StatusCode::CONFLICT, "群主不能直接退出群组"));
    }
    let changed = sqlx::query("DELETE FROM im_chat_group_user WHERE group_id=? AND user_id=?")
        .bind(id)
        .bind(member.id)
        .execute(&state.pool)
        .await
        .map_err(db_error)?;
    if changed.rows_affected() == 0 {
        return Err(invalid("尚未加入群组"));
    }
    Ok(Json(serde_json::json!({"left":true})))
}

async fn approve_group_member(
    State(state): State<AppState>,
    Extension(member): Extension<Member>,
    Path((id, user_id)): Path<(i64, i64)>,
) -> ApiResult<serde_json::Value> {
    let admin:Option<i64>=sqlx::query_scalar("SELECT admin_flag FROM im_chat_group_user WHERE group_id=? AND user_id=? AND user_status=1")
        .bind(id).bind(member.id).fetch_optional(&state.pool).await.map_err(db_error)?;
    if admin != Some(1) {
        return Err(AppError(StatusCode::FORBIDDEN, "只有群管理员可以审核"));
    }
    let changed=sqlx::query("UPDATE im_chat_group_user SET user_status=1 WHERE group_id=? AND user_id=? AND user_status=0")
        .bind(id).bind(user_id).execute(&state.pool).await.map_err(db_error)?;
    if changed.rows_affected() == 0 {
        return Err(invalid("没有待审核成员"));
    }
    Ok(Json(serde_json::json!({"approved":true})))
}

async fn group_requests(
    State(state): State<AppState>,
    Extension(member): Extension<Member>,
    Path(id): Path<i64>,
) -> ApiResult<Vec<PendingUser>> {
    let admin:Option<i64>=sqlx::query_scalar("SELECT admin_flag FROM im_chat_group_user WHERE group_id=? AND user_id=? AND user_status=1")
        .bind(id).bind(member.id).fetch_optional(&state.pool).await.map_err(db_error)?;
    if admin != Some(1) {
        return Err(AppError(StatusCode::FORBIDDEN, "只有群管理员可以查看申请"));
    }
    Ok(Json(sqlx::query_as::<_,PendingUser>("SELECT u.id,u.username,u.avatar FROM im_chat_group_user gu JOIN user u ON u.id=gu.user_id WHERE gu.group_id=? AND gu.user_status=0 AND u.deleted=0 AND u.user_status=1 ORDER BY gu.id DESC LIMIT 100")
        .bind(id).fetch_all(&state.pool).await.map_err(db_error)?))
}

async fn group_history(
    State(state): State<AppState>,
    Extension(member): Extension<Member>,
    Path(id): Path<i64>,
) -> ApiResult<Vec<GroupMessage>> {
    if !member_of(&state.pool, id, member.id).await? {
        return Err(AppError(StatusCode::FORBIDDEN, "你不在群组中"));
    }
    let mut rows=sqlx::query_as::<_,GroupMessage>("SELECT id,group_id,from_id,content,create_time FROM im_chat_user_group_message WHERE group_id=? ORDER BY id DESC LIMIT 100")
        .bind(id).fetch_all(&state.pool).await.map_err(db_error)?;
    rows.reverse();
    Ok(Json(rows))
}
