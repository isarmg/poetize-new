use crate::{ApiResult, AppState, db_error};
use axum::{Json, extract::State};
use serde::Serialize;

#[derive(Serialize, sqlx::FromRow)]
pub(super) struct SiteInfo {
    web_name: Option<String>,
    web_title: Option<String>,
    notices: Option<String>,
    footer: Option<String>,
    background_image: Option<String>,
    avatar: Option<String>,
    random_avatar: Option<String>,
    random_name: Option<String>,
    random_cover: Option<String>,
    waifu_json: Option<String>,
}
pub(super) async fn site_info(State(state): State<AppState>) -> ApiResult<SiteInfo> {
    Ok(Json(sqlx::query_as::<_,SiteInfo>("SELECT web_name,web_title,notices,footer,background_image,avatar,random_avatar,random_name,random_cover,waifu_json FROM web_info ORDER BY id LIMIT 1")
        .fetch_optional(&state.pool).await.map_err(db_error)?.unwrap_or(SiteInfo {web_name:Some("XOCS".into()),web_title:Some("相信记录的力量".into()),notices:None,footer:None,background_image:None,avatar:None,random_avatar:None,random_name:None,random_cover:None,waifu_json:None})))
}

#[derive(Serialize, sqlx::FromRow)]
pub(super) struct Resource {
    id: i64,
    path: Option<String>,
    resource_type: Option<String>,
    original_name: Option<String>,
    mime_type: Option<String>,
}
pub(super) async fn resources(State(state): State<AppState>) -> ApiResult<Vec<Resource>> {
    Ok(Json(sqlx::query_as::<_,Resource>("SELECT id,path,type AS resource_type,original_name,mime_type FROM resource WHERE status=1 ORDER BY id DESC LIMIT 100")
        .fetch_all(&state.pool).await.map_err(db_error)?))
}
#[derive(Serialize, sqlx::FromRow)]
pub(super) struct Friend {
    id: i64,
    title: Option<String>,
    url: Option<String>,
    cover: Option<String>,
    introduction: Option<String>,
}
pub(super) async fn friends(State(state): State<AppState>) -> ApiResult<Vec<Friend>> {
    Ok(Json(sqlx::query_as::<_,Friend>("SELECT id,title,url,cover,introduction FROM resource_path WHERE status=1 AND type='friendUrl' ORDER BY id DESC LIMIT 100")
        .fetch_all(&state.pool).await.map_err(db_error)?))
}
