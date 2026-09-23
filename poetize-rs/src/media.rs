use std::{
    fs::OpenOptions,
    io::Write,
    os::unix::fs::OpenOptionsExt,
    time::{SystemTime, UNIX_EPOCH},
};

use axum::{
    Json, Router,
    body::Bytes,
    extract::{DefaultBodyLimit, Extension, State},
    http::{HeaderMap, StatusCode},
    routing::post,
};
use serde::Serialize;
use sha2::{Digest, Sha256};

use crate::{ApiResult, AppError, AppState, auth::Member, db_error, invalid};

const MAX_IMAGE_BYTES: usize = 10 * 1024 * 1024;
const MAX_MEMBER_IMAGE_BYTES: usize = 5 * 1024 * 1024;

pub fn admin_routes() -> Router<AppState> {
    Router::new().route(
        "/api/v2/content/upload",
        post(upload).layer(DefaultBodyLimit::max(MAX_IMAGE_BYTES)),
    )
}

pub fn member_routes() -> Router<AppState> {
    Router::new().route(
        "/api/v2/members/upload",
        post(member_upload).layer(DefaultBodyLimit::max(MAX_MEMBER_IMAGE_BYTES)),
    )
}

#[derive(Serialize)]
struct UploadedImage {
    id: i64,
    path: String,
    mime_type: &'static str,
    size: usize,
}

fn image_type(bytes: &[u8]) -> Option<(&'static str, &'static str)> {
    if bytes.starts_with(b"\x89PNG\r\n\x1a\n") {
        return Some(("png", "image/png"));
    }
    if bytes.starts_with(b"\xff\xd8\xff") {
        return Some(("jpg", "image/jpeg"));
    }
    if bytes.starts_with(b"GIF87a") || bytes.starts_with(b"GIF89a") {
        return Some(("gif", "image/gif"));
    }
    if bytes.len() >= 12 && bytes.starts_with(b"RIFF") && &bytes[8..12] == b"WEBP" {
        return Some(("webp", "image/webp"));
    }
    None
}

async fn upload(
    State(state): State<AppState>,
    headers: HeaderMap,
    body: Bytes,
) -> ApiResult<UploadedImage> {
    let author = crate::site_author_id(&state.pool).await?;
    save_image(&state, &headers, body, author, MAX_IMAGE_BYTES).await
}

async fn member_upload(
    State(state): State<AppState>,
    Extension(member): Extension<Member>,
    headers: HeaderMap,
    body: Bytes,
) -> ApiResult<UploadedImage> {
    if body.is_empty() || body.len() > MAX_MEMBER_IMAGE_BYTES || image_type(&body).is_none() {
        return Err(invalid("图片大小或格式无效"));
    }
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|_| AppError(StatusCode::INTERNAL_SERVER_ERROR, "时钟不可用"))?
        .as_secs() as i64;
    let count:i64=sqlx::query_scalar("INSERT INTO member_write_limits(user_id,kind,window_start,count) VALUES(?,'member_upload',?,1) ON CONFLICT(user_id,kind) DO UPDATE SET count=CASE WHEN window_start<? THEN 1 ELSE count+1 END,window_start=CASE WHEN window_start<? THEN excluded.window_start ELSE window_start END RETURNING count")
        .bind(member.id).bind(now).bind(now-86400).bind(now-86400)
        .fetch_one(&state.pool).await.map_err(db_error)?;
    if count > 20 {
        return Err(AppError(
            StatusCode::TOO_MANY_REQUESTS,
            "今天上传得太频繁了",
        ));
    }
    let uploaded = save_image(&state, &headers, body, member.id, MAX_MEMBER_IMAGE_BYTES).await?;
    sqlx::query("INSERT OR IGNORE INTO member_images(user_id,path) VALUES(?,?)")
        .bind(member.id)
        .bind(&uploaded.0.path)
        .execute(&state.pool)
        .await
        .map_err(db_error)?;
    Ok(uploaded)
}

async fn save_image(
    state: &AppState,
    headers: &HeaderMap,
    body: Bytes,
    owner: i64,
    limit: usize,
) -> ApiResult<UploadedImage> {
    if body.is_empty() || body.len() > limit {
        return Err(invalid("图片大小无效"));
    }
    let (extension, mime_type) =
        image_type(&body).ok_or_else(|| invalid("仅支持 PNG、JPEG、GIF 和 WebP 图片"))?;
    let original_name = headers
        .get("x-file-name")
        .and_then(|v| v.to_str().ok())
        .filter(|v| v.len() <= 200 && !v.contains('/') && !v.contains('\\'));
    let hash = format!("{:x}", Sha256::digest(&body));
    let filename = format!("{hash}.{extension}");
    let target = state.media.join(&filename);
    let content = body.clone();
    tokio::task::spawn_blocking(move || -> Result<(), std::io::Error> {
        match OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .open(&target)
        {
            Ok(mut file) => file.write_all(&content),
            Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists && target.is_file() => {
                Ok(())
            }
            Err(error) => Err(error),
        }
    })
    .await
    .map_err(|_| AppError(StatusCode::INTERNAL_SERVER_ERROR, "图片保存失败"))?
    .map_err(|_| AppError(StatusCode::INTERNAL_SERVER_ERROR, "图片保存失败"))?;
    let path = format!("/media/{filename}");
    sqlx::query("INSERT INTO resource(user_id,type,path,size,original_name,mime_type,status,store_type) VALUES(?,'image',?,?,?,?,1,'local') ON CONFLICT(path) DO NOTHING")
        .bind(owner)
        .bind(&path).bind(body.len() as i64).bind(original_name).bind(mime_type)
        .execute(&state.pool).await.map_err(db_error)?;
    let id: i64 = sqlx::query_scalar("SELECT id FROM resource WHERE path=?")
        .bind(&path)
        .fetch_one(&state.pool)
        .await
        .map_err(db_error)?;
    Ok(Json(UploadedImage {
        id,
        path,
        mime_type,
        size: body.len(),
    }))
}
