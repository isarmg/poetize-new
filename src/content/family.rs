use crate::{ApiResult, AppState, db_error};
use axum::{Json, extract::State};
use serde::Serialize;

#[derive(Serialize, sqlx::FromRow)]
pub(super) struct Family {
    id: i64,
    bg_cover: Option<String>,
    man_cover: Option<String>,
    woman_cover: Option<String>,
    man_name: Option<String>,
    woman_name: Option<String>,
    timing: Option<String>,
    countdown_title: Option<String>,
    countdown_time: Option<String>,
    family_info: Option<String>,
    like_count: i64,
}
pub(super) async fn family(State(state): State<AppState>) -> ApiResult<Vec<Family>> {
    Ok(Json(sqlx::query_as::<_,Family>("SELECT id,bg_cover,man_cover,woman_cover,man_name,woman_name,timing,countdown_title,countdown_time,family_info,like_count FROM family WHERE status=1 ORDER BY id DESC LIMIT 20")
        .fetch_all(&state.pool).await.map_err(db_error)?))
}
