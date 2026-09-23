use crate::{
    ApiResult, AppError, AppState, absent,
    auth::{self, Member},
    db_error, invalid,
};
use axum::{
    Json, Router,
    extract::{ConnectInfo, Extension, Path, Query, State},
    http::StatusCode,
    routing::{get, post},
};
use serde::{Deserialize, Serialize};
use sqlx::SqlitePool;
use std::net::SocketAddr;

pub fn public_routes() -> Router<AppState> {
    Router::new()
        .route("/api/v2/site", get(site_info))
        .route("/api/v2/articles", get(public_articles))
        .route("/api/v2/articles/{id}", get(public_article))
        .route("/api/v2/articles/{id}/access", get(article_access))
        .route("/api/v2/articles/{id}/unlock", post(unlock_article))
        .route("/api/v2/articles/{id}/news", get(article_news))
        .route("/api/v2/categories", get(categories))
        .route("/api/v2/site/stats", get(public_site_stats))
        .route("/api/v2/labels", get(public_labels))
        .route("/api/v2/comments", get(comments))
        .route("/api/v2/comments/{id}/replies", get(comment_replies))
        .route("/api/v2/message-comments", get(message_comments))
        .route(
            "/api/v2/message-comments/{id}/replies",
            get(message_comment_replies),
        )
        .route("/api/v2/love-comments", get(love_comments))
        .route(
            "/api/v2/love-comments/{id}/replies",
            get(love_comment_replies),
        )
        .route("/api/v2/resources", get(resources))
        .route("/api/v2/friends", get(friends))
        .route("/api/v2/notes", get(notes))
        .route("/api/v2/notes/page", get(note_page))
        .route("/api/v2/tree-hole", get(tree_hole))
        .route("/api/v2/tree-hole/guest", post(create_guest_tree_hole))
        .route("/api/v2/links", get(links))
        .route("/api/v2/links/page", get(link_page))
        .route("/api/v2/links/classes", get(link_classes))
        .route("/api/v2/family", get(family))
}

pub fn admin_routes() -> Router<AppState> {
    Router::new()
        .route(
            "/api/v2/content/articles",
            get(admin_articles).post(create_article),
        )
        .route(
            "/api/v2/content/articles/{id}",
            get(admin_article)
                .put(update_article)
                .delete(delete_article),
        )
        .route(
            "/api/v2/content/articles/{id}/news",
            get(admin_article_news).post(admin_create_article_news),
        )
        .route(
            "/api/v2/content/articles/{id}/news/{news_id}",
            axum::routing::delete(admin_delete_article_news),
        )
        .route("/api/v2/content/categories", post(create_category))
        .route(
            "/api/v2/content/categories/{id}",
            axum::routing::put(update_category).delete(delete_category),
        )
}

pub fn member_routes() -> Router<AppState> {
    Router::new()
        .route("/api/v2/comments", post(create_comment))
        .route("/api/v2/message-comments", post(create_message_comment))
        .route("/api/v2/love-comments", post(create_love_comment))
        .route(
            "/api/v2/love-comments/{id}",
            axum::routing::delete(delete_love_comment),
        )
        .route(
            "/api/v2/message-comments/{id}",
            axum::routing::delete(delete_message_comment),
        )
        .route(
            "/api/v2/comments/{id}",
            axum::routing::delete(delete_comment),
        )
        .route("/api/v2/notes", post(create_note))
        .route("/api/v2/notes/{id}", axum::routing::delete(delete_note))
        .route("/api/v2/members/notes", get(member_notes))
        .route("/api/v2/tree-hole", post(create_tree_hole))
        .route("/api/v2/links/friend-submissions", post(submit_friend_link))
        .route("/api/v2/family/submissions", post(submit_family))
        .route("/api/v2/articles/{id}/news", post(create_article_news))
        .route(
            "/api/v2/articles/{id}/news/{news_id}",
            axum::routing::delete(delete_article_news),
        )
}

#[derive(Serialize, sqlx::FromRow)]
struct ArticleSummary {
    id: i64,
    article_title: String,
    article_cover: Option<String>,
    sort_id: i64,
    label_id: i64,
    sort_name: Option<String>,
    label_name: Option<String>,
    view_count: i64,
    like_count: i64,
    comment_count: i64,
    recommend_status: i64,
    view_status: i64,
    create_time: Option<String>,
    excerpt: Option<String>,
    search_snippet: Option<String>,
}

#[derive(Serialize, sqlx::FromRow)]
struct Article {
    id: i64,
    user_id: i64,
    article_title: String,
    article_content: String,
    article_cover: Option<String>,
    video_url: Option<String>,
    sort_id: i64,
    label_id: i64,
    view_count: i64,
    like_count: i64,
    comment_count: i64,
    username: Option<String>,
    sort_name: Option<String>,
    label_name: Option<String>,
    recommend_status: i64,
    comment_status: i64,
    view_status: i64,
    password_required: i64,
    tips: Option<String>,
    create_time: Option<String>,
    update_time: Option<String>,
}

const ARTICLE_SUMMARY_SQL: &str = "SELECT id,article_title,article_cover,sort_id,label_id,(SELECT sort_name FROM sort WHERE sort.id=article.sort_id) AS sort_name,(SELECT label_name FROM label WHERE label.id=article.label_id) AS label_name,view_count,like_count,(SELECT COUNT(*) FROM comment WHERE source=article.id AND type='article') AS comment_count,recommend_status,view_status,create_time,CASE WHEN password IS NULL THEN substr(article_content,1,400) ELSE NULL END AS excerpt,CASE WHEN ?2<>'' AND password IS NULL AND article_title NOT LIKE ?3 ESCAPE '\\' THEN substr(article_content,max(1,instr(lower(article_content),lower(?2))-48),160) ELSE NULL END AS search_snippet FROM article";
const ARTICLE_SQL: &str = "SELECT id,user_id,article_title,article_content,article_cover,video_url,sort_id,label_id,view_count,like_count,(SELECT COUNT(*) FROM comment WHERE source=article.id AND type='article') AS comment_count,(SELECT username FROM user WHERE user.id=article.user_id) AS username,(SELECT sort_name FROM sort WHERE sort.id=article.sort_id) AS sort_name,(SELECT label_name FROM label WHERE label.id=article.label_id) AS label_name,recommend_status,comment_status,view_status,CASE WHEN password IS NULL THEN 0 ELSE 1 END AS password_required,tips,create_time,update_time FROM article";

#[derive(Deserialize)]
struct ArticleQuery {
    page: Option<i64>,
    size: Option<i64>,
    sort_id: Option<i64>,
    label_id: Option<i64>,
    search: Option<String>,
    recommended: Option<bool>,
}

#[derive(Serialize)]
struct PageResult<T> {
    items: Vec<T>,
    total: i64,
    page: i64,
    size: i64,
}

async fn list_articles(
    pool: &SqlitePool,
    query: ArticleQuery,
    public: bool,
) -> Result<PageResult<ArticleSummary>, AppError> {
    let page = query.page.unwrap_or(1).clamp(1, 100_000);
    let size = query.size.unwrap_or(12).clamp(1, 100);
    let search = query.search.unwrap_or_default().trim().to_owned();
    if search.len() > 200 {
        return Err(invalid("搜索词过长"));
    }
    let pattern = format!(
        "%{}%",
        search
            .replace('\\', "\\\\")
            .replace('%', "\\%")
            .replace('_', "\\_")
    );
    let predicate = if public {
        "deleted=0 AND (view_status=1 OR password IS NOT NULL)"
    } else {
        "deleted=0"
    };
    let recommended = query.recommended.map(i64::from);
    let phrase = format!("\"{}\"", search.replace('"', "\"\""));
    let body_match = if public {
        "(password IS NULL AND article_content LIKE ?3 ESCAPE '\\')"
    } else {
        "article_content LIKE ?3 ESCAPE '\\'"
    };
    let filter = format!(
        "{predicate} AND (?1 IS NULL OR sort_id=?1) AND (?6 IS NULL OR label_id=?6) AND (?4 IS NULL OR recommend_status=?4) AND (?2='' OR ((article_title LIKE ?3 ESCAPE '\\' OR {body_match}) AND (length(?2)<3 OR id IN (SELECT rowid FROM article_search WHERE article_search MATCH ?5))))"
    );
    let count_sql = format!("SELECT COUNT(*) FROM article WHERE {filter}");
    let total: i64 = sqlx::query_scalar(&count_sql)
        .bind(query.sort_id)
        .bind(&search)
        .bind(&pattern)
        .bind(recommended)
        .bind(&phrase)
        .bind(query.label_id)
        .fetch_one(pool)
        .await
        .map_err(db_error)?;
    let list_sql = format!(
        "{ARTICLE_SUMMARY_SQL} WHERE {filter} ORDER BY CASE WHEN ?2<>'' AND article_title LIKE ?3 ESCAPE '\\' THEN 0 ELSE 1 END,create_time DESC,id DESC LIMIT ?7 OFFSET ?8"
    );
    let items = sqlx::query_as::<_, ArticleSummary>(&list_sql)
        .bind(query.sort_id)
        .bind(&search)
        .bind(&pattern)
        .bind(recommended)
        .bind(&phrase)
        .bind(query.label_id)
        .bind(size)
        .bind((page - 1) * size)
        .fetch_all(pool)
        .await
        .map_err(db_error)?;
    Ok(PageResult {
        items,
        total,
        page,
        size,
    })
}

async fn public_articles(
    State(state): State<AppState>,
    Query(query): Query<ArticleQuery>,
) -> ApiResult<PageResult<ArticleSummary>> {
    Ok(Json(list_articles(&state.pool, query, true).await?))
}
async fn admin_articles(
    State(state): State<AppState>,
    Query(query): Query<ArticleQuery>,
) -> ApiResult<PageResult<ArticleSummary>> {
    Ok(Json(list_articles(&state.pool, query, false).await?))
}

async fn load_article(pool: &SqlitePool, id: i64, admin: bool) -> Result<Article, AppError> {
    let condition = if admin {
        "id=? AND deleted=0"
    } else {
        "id=? AND deleted=0 AND view_status=1 AND password IS NULL"
    };
    sqlx::query_as::<_, Article>(&format!("{ARTICLE_SQL} WHERE {condition}"))
        .bind(id)
        .fetch_optional(pool)
        .await
        .map_err(db_error)?
        .ok_or_else(absent)
}

async fn public_article(State(state): State<AppState>, Path(id): Path<i64>) -> ApiResult<Article> {
    let article = load_article(&state.pool, id, false).await?;
    sqlx::query("UPDATE article SET view_count=view_count+1 WHERE id=?")
        .bind(id)
        .execute(&state.pool)
        .await
        .map_err(db_error)?;
    Ok(Json(article))
}
#[derive(Serialize, sqlx::FromRow)]
struct ArticleNews {
    id: i64,
    user_id: Option<i64>,
    username: Option<String>,
    content: String,
    create_time: Option<String>,
}
const ARTICLE_NEWS_SQL: &str = "SELECT w.id,w.user_id,u.username,w.content,w.create_time FROM wei_yan w LEFT JOIN user u ON u.id=w.user_id";
async fn article_news(
    State(state): State<AppState>,
    Path(id): Path<i64>,
) -> ApiResult<Vec<ArticleNews>> {
    load_article(&state.pool, id, false).await?;
    let rows=sqlx::query_as::<_,ArticleNews>(&format!("{ARTICLE_NEWS_SQL} WHERE w.type='news' AND w.source=? AND w.is_public=1 ORDER BY w.create_time DESC,w.id DESC LIMIT 100"))
        .bind(id).fetch_all(&state.pool).await.map_err(db_error)?;
    Ok(Json(rows))
}
async fn admin_article_news(
    State(state): State<AppState>,
    Path(id): Path<i64>,
) -> ApiResult<Vec<ArticleNews>> {
    load_article(&state.pool, id, true).await?;
    let rows=sqlx::query_as::<_,ArticleNews>(&format!("{ARTICLE_NEWS_SQL} WHERE w.type='news' AND w.source=? ORDER BY w.create_time DESC,w.id DESC LIMIT 100"))
        .bind(id).fetch_all(&state.pool).await.map_err(db_error)?;
    Ok(Json(rows))
}
#[derive(Deserialize)]
struct ArticleNewsInput {
    content: String,
    create_time: Option<String>,
}
async fn admin_create_article_news(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Json(input): Json<ArticleNewsInput>,
) -> ApiResult<ArticleNews> {
    let article = load_article(&state.pool, id, true).await?;
    Ok(Json(
        insert_article_news(&state, id, article.user_id, input).await?,
    ))
}
async fn admin_delete_article_news(
    State(state): State<AppState>,
    Path((article_id, news_id)): Path<(i64, i64)>,
) -> ApiResult<serde_json::Value> {
    let changed = sqlx::query("DELETE FROM wei_yan WHERE id=? AND source=? AND type='news'")
        .bind(news_id)
        .bind(article_id)
        .execute(&state.pool)
        .await
        .map_err(db_error)?;
    if changed.rows_affected() == 0 {
        return Err(absent());
    }
    Ok(Json(serde_json::json!({"deleted":true})))
}
async fn insert_article_news(
    state: &AppState,
    id: i64,
    author: i64,
    input: ArticleNewsInput,
) -> Result<ArticleNews, AppError> {
    let content = input.content.trim();
    if content.is_empty() || content.chars().count() > 1024 {
        return Err(invalid("进展内容应为 1 至 1024 字"));
    }
    let time = if let Some(raw) = input.create_time.filter(|value| !value.trim().is_empty()) {
        let parsed: Option<String> = sqlx::query_scalar("SELECT datetime(?)")
            .bind(&raw)
            .fetch_one(&state.pool)
            .await
            .map_err(db_error)?;
        Some(parsed.ok_or_else(|| invalid("日期时间无效"))?)
    } else {
        None
    };
    let id_new=sqlx::query("INSERT INTO wei_yan(user_id,content,type,source,is_public,create_time) VALUES(?,?,'news',?,1,COALESCE(?,CURRENT_TIMESTAMP))")
        .bind(author).bind(content).bind(id).bind(time).execute(&state.pool).await.map_err(db_error)?.last_insert_rowid();
    sqlx::query_as::<_, ArticleNews>(&format!("{ARTICLE_NEWS_SQL} WHERE w.id=?"))
        .bind(id_new)
        .fetch_one(&state.pool)
        .await
        .map_err(db_error)
}
async fn create_article_news(
    State(state): State<AppState>,
    Extension(member): Extension<Member>,
    Path(id): Path<i64>,
    Json(input): Json<ArticleNewsInput>,
) -> ApiResult<ArticleNews> {
    let owner: Option<i64> =
        sqlx::query_scalar("SELECT user_id FROM article WHERE id=? AND deleted=0")
            .bind(id)
            .fetch_optional(&state.pool)
            .await
            .map_err(db_error)?;
    if owner != Some(member.id) {
        return Err(AppError(StatusCode::FORBIDDEN, "只有文章作者可以发布进展"));
    }
    Ok(Json(
        insert_article_news(&state, id, member.id, input).await?,
    ))
}
async fn delete_article_news(
    State(state): State<AppState>,
    Extension(member): Extension<Member>,
    Path((article_id, news_id)): Path<(i64, i64)>,
) -> ApiResult<serde_json::Value> {
    let changed =
        sqlx::query("DELETE FROM wei_yan WHERE id=? AND source=? AND type='news' AND user_id=?")
            .bind(news_id)
            .bind(article_id)
            .bind(member.id)
            .execute(&state.pool)
            .await
            .map_err(db_error)?;
    if changed.rows_affected() == 0 {
        return Err(absent());
    }
    Ok(Json(serde_json::json!({"deleted":true})))
}
#[derive(Serialize, sqlx::FromRow)]
struct ArticleAccess {
    password_required: i64,
    tips: Option<String>,
}
async fn article_access(
    State(state): State<AppState>,
    Path(id): Path<i64>,
) -> ApiResult<ArticleAccess> {
    Ok(Json(sqlx::query_as::<_,ArticleAccess>("SELECT CASE WHEN password IS NULL THEN 0 ELSE 1 END AS password_required,tips FROM article WHERE id=? AND deleted=0 AND (view_status=1 OR password IS NOT NULL)")
        .bind(id).fetch_optional(&state.pool).await.map_err(db_error)?.ok_or_else(absent)?))
}
async fn admin_article(State(state): State<AppState>, Path(id): Path<i64>) -> ApiResult<Article> {
    Ok(Json(load_article(&state.pool, id, true).await?))
}

#[derive(Deserialize)]
struct UnlockRequest {
    password: String,
}
async fn unlock_article(
    State(state): State<AppState>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    Path(id): Path<i64>,
    Json(input): Json<UnlockRequest>,
) -> ApiResult<Article> {
    if input.password.len() > 1024 {
        return Err(invalid("访问密码无效"));
    }
    let stored: Option<String> = sqlx::query_scalar(
        "SELECT password FROM article WHERE id=? AND deleted=0 AND password IS NOT NULL",
    )
    .bind(id)
    .fetch_optional(&state.pool)
    .await
    .map_err(db_error)?
    .ok_or_else(absent)?;
    let key = auth::attempt_key("article", &peer.ip().to_string(), &id.to_string());
    auth::record_attempt(&state.pool, &key, 10)
        .await
        .map_err(|error| {
            if error.0 == StatusCode::TOO_MANY_REQUESTS {
                AppError(
                    StatusCode::TOO_MANY_REQUESTS,
                    "访问密码尝试过多，请稍后再试",
                )
            } else {
                error
            }
        })?;
    let allowed = stored
        .as_deref()
        .is_none_or(|hash| sarmg_admin_auth::verify_password(&input.password, hash));
    if !allowed {
        return Err(AppError(StatusCode::FORBIDDEN, "访问密码错误"));
    }
    sqlx::query("DELETE FROM member_login_failures WHERE failure_key=?")
        .bind(&key)
        .execute(&state.pool)
        .await
        .map_err(db_error)?;
    sqlx::query("UPDATE article SET view_count=view_count+1 WHERE id=?")
        .bind(id)
        .execute(&state.pool)
        .await
        .map_err(db_error)?;
    Ok(Json(load_article(&state.pool, id, true).await?))
}

#[derive(Deserialize)]
struct ArticleInput {
    article_title: String,
    article_content: String,
    article_cover: Option<String>,
    video_url: Option<String>,
    sort_id: i64,
    label_id: i64,
    view_status: bool,
    recommend_status: bool,
    comment_status: bool,
    password: Option<String>,
    tips: Option<String>,
}
fn validate_article(input: &ArticleInput, has_existing_password: bool) -> Result<(), AppError> {
    if input.article_title.trim().is_empty() || input.article_title.chars().count() > 120 {
        return Err(invalid("文章标题无效"));
    }
    if input.article_content.trim().is_empty() || input.article_content.len() > 2_000_000 {
        return Err(invalid("文章内容无效"));
    }
    if input.sort_id < 1 || input.label_id < 1 {
        return Err(invalid("请选择分类和标签"));
    }
    if !input.view_status && input.password.is_none() && !has_existing_password {
        return Err(invalid("加密文章必须设置访问密码"));
    }
    if input.view_status && input.password.is_some() {
        return Err(invalid("公开文章不可设置访问密码"));
    }
    if let Some(password) = &input.password {
        sarmg_admin_auth::validate_password(password)
            .map_err(|_| invalid("文章密码至少需要 12 字节"))?;
    }
    Ok(())
}

async fn create_article(
    State(state): State<AppState>,
    Json(input): Json<ArticleInput>,
) -> ApiResult<Article> {
    validate_article(&input, false)?;
    let password = input
        .password
        .as_deref()
        .map(sarmg_admin_auth::hash_password)
        .transpose()
        .map_err(|_| invalid("文章密码无效"))?;
    let author = crate::site_author_id(&state.pool).await?;
    let id = sqlx::query("INSERT INTO article(user_id,sort_id,label_id,article_cover,article_title,article_content,video_url,view_status,recommend_status,comment_status,password,tips) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")
        .bind(author)
        .bind(input.sort_id).bind(input.label_id).bind(input.article_cover).bind(input.article_title).bind(input.article_content).bind(input.video_url)
        .bind(i64::from(input.view_status)).bind(i64::from(input.recommend_status)).bind(i64::from(input.comment_status)).bind(password).bind(input.tips)
        .execute(&state.pool).await.map_err(db_error)?.last_insert_rowid();
    Ok(Json(load_article(&state.pool, id, true).await?))
}

async fn update_article(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Json(input): Json<ArticleInput>,
) -> ApiResult<Article> {
    let old_password: Option<Option<String>> =
        sqlx::query_scalar("SELECT password FROM article WHERE id=? AND deleted=0")
            .bind(id)
            .fetch_optional(&state.pool)
            .await
            .map_err(db_error)?;
    let Some(old_password) = old_password else {
        return Err(absent());
    };
    validate_article(&input, old_password.is_some())?;
    let password = input
        .password
        .as_deref()
        .map(sarmg_admin_auth::hash_password)
        .transpose()
        .map_err(|_| invalid("文章密码无效"))?;
    let result = sqlx::query("UPDATE article SET sort_id=?,label_id=?,article_cover=?,article_title=?,article_content=?,video_url=?,view_status=?,recommend_status=?,comment_status=?,password=CASE WHEN ?=1 THEN NULL ELSE COALESCE(?,password) END,tips=?,update_time=CURRENT_TIMESTAMP WHERE id=? AND deleted=0")
        .bind(input.sort_id).bind(input.label_id).bind(input.article_cover).bind(input.article_title).bind(input.article_content).bind(input.video_url)
        .bind(i64::from(input.view_status)).bind(i64::from(input.recommend_status)).bind(i64::from(input.comment_status)).bind(i64::from(input.view_status)).bind(password).bind(input.tips).bind(id)
        .execute(&state.pool).await.map_err(db_error)?;
    if result.rows_affected() == 0 {
        return Err(absent());
    }
    Ok(Json(load_article(&state.pool, id, true).await?))
}

async fn delete_article(
    State(state): State<AppState>,
    Path(id): Path<i64>,
) -> ApiResult<serde_json::Value> {
    let result = sqlx::query(
        "UPDATE article SET deleted=1,update_time=CURRENT_TIMESTAMP WHERE id=? AND deleted=0",
    )
    .bind(id)
    .execute(&state.pool)
    .await
    .map_err(db_error)?;
    if result.rows_affected() == 0 {
        return Err(absent());
    }
    Ok(Json(serde_json::json!({"deleted":true})))
}

#[derive(Serialize, sqlx::FromRow)]
struct Category {
    id: i64,
    sort_name: String,
    sort_description: Option<String>,
    priority: Option<i64>,
    article_count: i64,
}
#[derive(Serialize, sqlx::FromRow)]
struct PublicSiteStats {
    article_count: i64,
    view_count: i64,
}
async fn public_site_stats(State(state): State<AppState>) -> ApiResult<PublicSiteStats> {
    Ok(Json(sqlx::query_as::<_, PublicSiteStats>("SELECT COUNT(*) AS article_count,COALESCE(SUM(view_count),0) AS view_count FROM article WHERE deleted=0 AND (view_status=1 OR password IS NOT NULL)")
        .fetch_one(&state.pool).await.map_err(db_error)?))
}
async fn categories(State(state): State<AppState>) -> ApiResult<Vec<Category>> {
    Ok(Json(
        sqlx::query_as::<_, Category>(
            "SELECT id,sort_name,sort_description,priority,(SELECT COUNT(*) FROM article a WHERE a.sort_id=sort.id AND a.deleted=0 AND (a.view_status=1 OR a.password IS NOT NULL)) AS article_count FROM sort ORDER BY priority DESC,id",
        )
        .fetch_all(&state.pool)
        .await
        .map_err(db_error)?,
    ))
}
#[derive(Deserialize)]
struct LabelQuery {
    sort_id: Option<i64>,
}
#[derive(Serialize, sqlx::FromRow)]
struct PublicLabel {
    id: i64,
    sort_id: i64,
    label_name: String,
    label_description: Option<String>,
    article_count: i64,
}
async fn public_labels(
    State(state): State<AppState>,
    Query(query): Query<LabelQuery>,
) -> ApiResult<Vec<PublicLabel>> {
    Ok(Json(sqlx::query_as::<_, PublicLabel>("SELECT l.id,l.sort_id,l.label_name,l.label_description,(SELECT COUNT(*) FROM article a WHERE a.label_id=l.id AND a.deleted=0 AND (a.view_status=1 OR a.password IS NOT NULL)) AS article_count FROM label l WHERE ? IS NULL OR l.sort_id=? ORDER BY l.id")
        .bind(query.sort_id).bind(query.sort_id).fetch_all(&state.pool).await.map_err(db_error)?))
}
#[derive(Deserialize)]
struct CategoryInput {
    name: String,
    description: Option<String>,
    priority: Option<i64>,
}
async fn create_category(
    State(state): State<AppState>,
    Json(input): Json<CategoryInput>,
) -> ApiResult<Category> {
    if input.name.trim().is_empty() || input.name.chars().count() > 64 {
        return Err(invalid("分类名称无效"));
    }
    let id = sqlx::query("INSERT INTO sort(sort_name,sort_description,priority) VALUES(?,?,?)")
        .bind(input.name)
        .bind(input.description)
        .bind(input.priority.unwrap_or(0))
        .execute(&state.pool)
        .await
        .map_err(db_error)?
        .last_insert_rowid();
    Ok(Json(
        sqlx::query_as(
            "SELECT id,sort_name,sort_description,priority,0 AS article_count FROM sort WHERE id=?",
        )
        .bind(id)
        .fetch_one(&state.pool)
        .await
        .map_err(db_error)?,
    ))
}
async fn update_category(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Json(input): Json<CategoryInput>,
) -> ApiResult<Category> {
    if input.name.trim().is_empty() || input.name.chars().count() > 64 {
        return Err(invalid("分类名称无效"));
    }
    let changed =
        sqlx::query("UPDATE sort SET sort_name=?,sort_description=?,priority=? WHERE id=?")
            .bind(input.name.trim())
            .bind(input.description)
            .bind(input.priority.unwrap_or(0))
            .bind(id)
            .execute(&state.pool)
            .await
            .map_err(db_error)?;
    if changed.rows_affected() == 0 {
        return Err(absent());
    }
    Ok(Json(
        sqlx::query_as("SELECT id,sort_name,sort_description,priority,(SELECT COUNT(*) FROM article a WHERE a.sort_id=sort.id AND a.deleted=0 AND (a.view_status=1 OR a.password IS NOT NULL)) AS article_count FROM sort WHERE id=?")
            .bind(id)
            .fetch_one(&state.pool)
            .await
            .map_err(db_error)?,
    ))
}
async fn delete_category(
    State(state): State<AppState>,
    Path(id): Path<i64>,
) -> ApiResult<serde_json::Value> {
    let used: i64 =
        sqlx::query_scalar("SELECT COUNT(*) FROM article WHERE sort_id=? AND deleted=0")
            .bind(id)
            .fetch_one(&state.pool)
            .await
            .map_err(db_error)?;
    if used > 0 {
        return Err(AppError(StatusCode::CONFLICT, "分类中还有文章"));
    }
    let result = sqlx::query("DELETE FROM sort WHERE id=?")
        .bind(id)
        .execute(&state.pool)
        .await
        .map_err(db_error)?;
    if result.rows_affected() == 0 {
        return Err(absent());
    }
    Ok(Json(serde_json::json!({"deleted":true})))
}

#[derive(Serialize, sqlx::FromRow)]
struct SiteInfo {
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
async fn site_info(State(state): State<AppState>) -> ApiResult<SiteInfo> {
    Ok(Json(sqlx::query_as::<_,SiteInfo>("SELECT web_name,web_title,notices,footer,background_image,avatar,random_avatar,random_name,random_cover,waifu_json FROM web_info ORDER BY id LIMIT 1")
        .fetch_optional(&state.pool).await.map_err(db_error)?.unwrap_or(SiteInfo {web_name:Some("POETIZE".into()),web_title:Some("相信记录的力量".into()),notices:None,footer:None,background_image:None,avatar:None,random_avatar:None,random_name:None,random_cover:None,waifu_json:None})))
}

#[derive(Deserialize)]
struct CommentsQuery {
    article_id: i64,
    page: Option<i64>,
    size: Option<i64>,
}
#[derive(Serialize, sqlx::FromRow)]
struct Comment {
    id: i64,
    user_id: Option<i64>,
    username: Option<String>,
    avatar: Option<String>,
    comment_content: String,
    create_time: Option<String>,
    parent_comment_id: Option<i64>,
    parent_username: Option<String>,
    floor_comment_id: Option<i64>,
    reply_count: i64,
}
const COMMENT_SQL: &str = "SELECT c.id,c.user_id,u.username,u.avatar,c.comment_content,c.create_time,c.parent_comment_id,pu.username AS parent_username,c.floor_comment_id,(SELECT COUNT(*) FROM comment child WHERE child.floor_comment_id=c.id AND child.type=c.type) AS reply_count FROM comment c LEFT JOIN user u ON u.id=c.user_id LEFT JOIN user pu ON pu.id=c.parent_user_id";
async fn visible_comment_article(state: &AppState, id: i64) -> Result<(), AppError> {
    let visible: Option<i64> = sqlx::query_scalar(
        "SELECT id FROM article WHERE id=? AND deleted=0 AND view_status=1 AND password IS NULL",
    )
    .bind(id)
    .fetch_optional(&state.pool)
    .await
    .map_err(db_error)?;
    if visible.is_none() {
        return Err(absent());
    }
    Ok(())
}
async fn comments(
    State(state): State<AppState>,
    Query(query): Query<CommentsQuery>,
) -> ApiResult<PageResult<Comment>> {
    visible_comment_article(&state, query.article_id).await?;
    let page = query.page.unwrap_or(1).clamp(1, 100_000);
    let size = query.size.unwrap_or(10).clamp(1, 50);
    let total:i64=sqlx::query_scalar("SELECT COUNT(*) FROM comment WHERE source=? AND type='article' AND (parent_comment_id IS NULL OR parent_comment_id=0)")
        .bind(query.article_id).fetch_one(&state.pool).await.map_err(db_error)?;
    let items=sqlx::query_as::<_,Comment>(&format!("{COMMENT_SQL} WHERE c.source=? AND c.type='article' AND (c.parent_comment_id IS NULL OR c.parent_comment_id=0) ORDER BY c.create_time DESC,c.id DESC LIMIT ? OFFSET ?"))
        .bind(query.article_id).bind(size).bind((page-1)*size).fetch_all(&state.pool).await.map_err(db_error)?;
    Ok(Json(PageResult {
        items,
        total,
        page,
        size,
    }))
}
#[derive(Deserialize)]
struct RepliesQuery {
    page: Option<i64>,
    size: Option<i64>,
}
async fn comment_replies(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Query(query): Query<RepliesQuery>,
) -> ApiResult<PageResult<Comment>> {
    let article:Option<i64>=sqlx::query_scalar("SELECT source FROM comment WHERE id=? AND type='article' AND (parent_comment_id IS NULL OR parent_comment_id=0)")
        .bind(id).fetch_optional(&state.pool).await.map_err(db_error)?;
    visible_comment_article(&state, article.ok_or_else(absent)?).await?;
    let page = query.page.unwrap_or(1).clamp(1, 100_000);
    let size = query.size.unwrap_or(5).clamp(1, 50);
    let total: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM comment WHERE floor_comment_id=? AND type='article'",
    )
    .bind(id)
    .fetch_one(&state.pool)
    .await
    .map_err(db_error)?;
    let items=sqlx::query_as::<_,Comment>(&format!("{COMMENT_SQL} WHERE c.floor_comment_id=? AND c.type='article' ORDER BY c.create_time,c.id LIMIT ? OFFSET ?"))
        .bind(id).bind(size).bind((page-1)*size).fetch_all(&state.pool).await.map_err(db_error)?;
    Ok(Json(PageResult {
        items,
        total,
        page,
        size,
    }))
}
async fn message_comments(
    State(state): State<AppState>,
    Query(query): Query<RepliesQuery>,
) -> ApiResult<PageResult<Comment>> {
    let page = query.page.unwrap_or(1).clamp(1, 100_000);
    let size = query.size.unwrap_or(10).clamp(1, 50);
    let total:i64=sqlx::query_scalar("SELECT COUNT(*) FROM comment WHERE source=0 AND type='message' AND (parent_comment_id IS NULL OR parent_comment_id=0)")
        .fetch_one(&state.pool).await.map_err(db_error)?;
    let items=sqlx::query_as::<_,Comment>(&format!("{COMMENT_SQL} WHERE c.source=0 AND c.type='message' AND (c.parent_comment_id IS NULL OR c.parent_comment_id=0) ORDER BY c.create_time DESC,c.id DESC LIMIT ? OFFSET ?"))
        .bind(size).bind((page-1)*size).fetch_all(&state.pool).await.map_err(db_error)?;
    Ok(Json(PageResult {
        items,
        total,
        page,
        size,
    }))
}
async fn message_comment_replies(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Query(query): Query<RepliesQuery>,
) -> ApiResult<PageResult<Comment>> {
    let root:Option<i64>=sqlx::query_scalar("SELECT id FROM comment WHERE id=? AND source=0 AND type='message' AND (parent_comment_id IS NULL OR parent_comment_id=0)")
        .bind(id).fetch_optional(&state.pool).await.map_err(db_error)?;
    if root.is_none() {
        return Err(absent());
    }
    let page = query.page.unwrap_or(1).clamp(1, 100_000);
    let size = query.size.unwrap_or(5).clamp(1, 50);
    let total: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM comment WHERE floor_comment_id=? AND source=0 AND type='message'",
    )
    .bind(id)
    .fetch_one(&state.pool)
    .await
    .map_err(db_error)?;
    let items=sqlx::query_as::<_,Comment>(&format!("{COMMENT_SQL} WHERE c.floor_comment_id=? AND c.source=0 AND c.type='message' ORDER BY c.create_time,c.id LIMIT ? OFFSET ?"))
        .bind(id).bind(size).bind((page-1)*size).fetch_all(&state.pool).await.map_err(db_error)?;
    Ok(Json(PageResult {
        items,
        total,
        page,
        size,
    }))
}
async fn love_comments(
    State(state): State<AppState>,
    Query(query): Query<RepliesQuery>,
) -> ApiResult<PageResult<Comment>> {
    let source = crate::site_author_id(&state.pool).await?;
    let page = query.page.unwrap_or(1).clamp(1, 100_000);
    let size = query.size.unwrap_or(10).clamp(1, 50);
    let total:i64=sqlx::query_scalar("SELECT COUNT(*) FROM comment WHERE source=? AND type='love' AND (parent_comment_id IS NULL OR parent_comment_id=0)")
        .bind(source).fetch_one(&state.pool).await.map_err(db_error)?;
    let items=sqlx::query_as::<_,Comment>(&format!("{COMMENT_SQL} WHERE c.source=? AND c.type='love' AND (c.parent_comment_id IS NULL OR c.parent_comment_id=0) ORDER BY c.create_time DESC,c.id DESC LIMIT ? OFFSET ?"))
        .bind(source).bind(size).bind((page-1)*size).fetch_all(&state.pool).await.map_err(db_error)?;
    Ok(Json(PageResult {
        items,
        total,
        page,
        size,
    }))
}
async fn love_comment_replies(
    State(state): State<AppState>,
    Path(id): Path<i64>,
    Query(query): Query<RepliesQuery>,
) -> ApiResult<PageResult<Comment>> {
    let source = crate::site_author_id(&state.pool).await?;
    let root:Option<i64>=sqlx::query_scalar("SELECT id FROM comment WHERE id=? AND source=? AND type='love' AND (parent_comment_id IS NULL OR parent_comment_id=0)")
        .bind(id).bind(source).fetch_optional(&state.pool).await.map_err(db_error)?;
    if root.is_none() {
        return Err(absent());
    }
    let page = query.page.unwrap_or(1).clamp(1, 100_000);
    let size = query.size.unwrap_or(5).clamp(1, 50);
    let total: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM comment WHERE floor_comment_id=? AND source=? AND type='love'",
    )
    .bind(id)
    .bind(source)
    .fetch_one(&state.pool)
    .await
    .map_err(db_error)?;
    let items=sqlx::query_as::<_,Comment>(&format!("{COMMENT_SQL} WHERE c.floor_comment_id=? AND c.source=? AND c.type='love' ORDER BY c.create_time,c.id LIMIT ? OFFSET ?"))
        .bind(id).bind(source).bind(size).bind((page-1)*size).fetch_all(&state.pool).await.map_err(db_error)?;
    Ok(Json(PageResult {
        items,
        total,
        page,
        size,
    }))
}

#[derive(Serialize, sqlx::FromRow)]
struct Resource {
    id: i64,
    path: Option<String>,
    resource_type: Option<String>,
    original_name: Option<String>,
    mime_type: Option<String>,
}
async fn resources(State(state): State<AppState>) -> ApiResult<Vec<Resource>> {
    Ok(Json(sqlx::query_as::<_,Resource>("SELECT id,path,type AS resource_type,original_name,mime_type FROM resource WHERE status=1 ORDER BY id DESC LIMIT 100")
        .fetch_all(&state.pool).await.map_err(db_error)?))
}
#[derive(Serialize, sqlx::FromRow)]
struct Friend {
    id: i64,
    title: Option<String>,
    url: Option<String>,
    cover: Option<String>,
    introduction: Option<String>,
}
async fn friends(State(state): State<AppState>) -> ApiResult<Vec<Friend>> {
    Ok(Json(sqlx::query_as::<_,Friend>("SELECT id,title,url,cover,introduction FROM resource_path WHERE status=1 AND type='friendUrl' ORDER BY id DESC LIMIT 100")
        .fetch_all(&state.pool).await.map_err(db_error)?))
}

#[derive(Serialize, sqlx::FromRow)]
struct Note {
    id: i64,
    user_id: Option<i64>,
    username: Option<String>,
    content: String,
    image_path: Option<String>,
    like_count: i64,
    is_public: i64,
    create_time: Option<String>,
}
async fn notes(State(state): State<AppState>) -> ApiResult<Vec<Note>> {
    Ok(Json(sqlx::query_as::<_,Note>("SELECT w.id,w.user_id,u.username,w.content,w.image_path,w.like_count,w.is_public,w.create_time FROM wei_yan w LEFT JOIN user u ON u.id=w.user_id WHERE w.is_public=1 AND w.type='friend' ORDER BY w.id DESC LIMIT 100")
        .fetch_all(&state.pool).await.map_err(db_error)?))
}
#[derive(Deserialize)]
struct NotePageQuery {
    page: Option<i64>,
    size: Option<i64>,
}
async fn list_note_page(
    state: &AppState,
    query: NotePageQuery,
    user: Option<i64>,
) -> Result<PageResult<Note>, AppError> {
    let page = query.page.unwrap_or(1).clamp(1, 100_000);
    let size = query.size.unwrap_or(10).clamp(1, 50);
    let author = match user {
        Some(id) => id,
        None => crate::site_author_id(&state.pool).await?,
    };
    let public = user.is_none();
    let total: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM wei_yan WHERE type='friend' AND user_id=? AND (?=0 OR is_public=1)",
    )
    .bind(author)
    .bind(i64::from(public))
    .fetch_one(&state.pool)
    .await
    .map_err(db_error)?;
    let items=sqlx::query_as::<_,Note>("SELECT w.id,w.user_id,u.username,w.content,w.image_path,w.like_count,w.is_public,w.create_time FROM wei_yan w LEFT JOIN user u ON u.id=w.user_id WHERE w.type='friend' AND w.user_id=? AND (?=0 OR w.is_public=1) ORDER BY w.id DESC LIMIT ? OFFSET ?")
        .bind(author).bind(i64::from(public)).bind(size).bind((page-1)*size)
        .fetch_all(&state.pool).await.map_err(db_error)?;
    Ok(PageResult {
        items,
        total,
        page,
        size,
    })
}
async fn note_page(
    State(state): State<AppState>,
    Query(query): Query<NotePageQuery>,
) -> ApiResult<PageResult<Note>> {
    Ok(Json(list_note_page(&state, query, None).await?))
}
async fn member_notes(
    State(state): State<AppState>,
    Extension(member): Extension<Member>,
    Query(query): Query<NotePageQuery>,
) -> ApiResult<PageResult<Note>> {
    Ok(Json(list_note_page(&state, query, Some(member.id)).await?))
}
#[derive(Deserialize)]
struct NoteInput {
    content: String,
    image_path: Option<String>,
    is_public: Option<bool>,
}
async fn valid_member_image(
    state: &AppState,
    member_id: i64,
    path: &Option<String>,
) -> Result<(), AppError> {
    if let Some(path) = path {
        if path.len() > 256 || !path.starts_with("/media/") {
            return Err(invalid("图片地址无效"));
        }
        let owned: i64 =
            sqlx::query_scalar("SELECT COUNT(*) FROM member_images WHERE user_id=? AND path=?")
                .bind(member_id)
                .bind(path)
                .fetch_one(&state.pool)
                .await
                .map_err(db_error)?;
        if owned == 0 {
            return Err(invalid("请先上传自己的图片"));
        }
    }
    Ok(())
}
async fn create_note(
    State(state): State<AppState>,
    Extension(member): Extension<Member>,
    Json(input): Json<NoteInput>,
) -> ApiResult<Note> {
    if (input.content.trim().is_empty() && input.image_path.is_none())
        || input.content.chars().count() > 1024
        || input.content.contains('<')
        || input.content.contains('>')
    {
        return Err(invalid("内容无效"));
    }
    valid_member_image(&state, member.id, &input.image_path).await?;
    let count: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM wei_yan WHERE user_id=? AND create_time>=datetime('now','-1 day')",
    )
    .bind(member.id)
    .fetch_one(&state.pool)
    .await
    .map_err(db_error)?;
    if count >= 10 {
        return Err(AppError(
            StatusCode::TOO_MANY_REQUESTS,
            "今天发布得太频繁了",
        ));
    }
    let id = sqlx::query(
        "INSERT INTO wei_yan(user_id,content,image_path,type,is_public) VALUES(?,?,?,'friend',?)",
    )
    .bind(member.id)
    .bind(input.content)
    .bind(input.image_path)
    .bind(i64::from(input.is_public.unwrap_or(true)))
    .execute(&state.pool)
    .await
    .map_err(db_error)?
    .last_insert_rowid();
    Ok(Json(sqlx::query_as("SELECT w.id,w.user_id,u.username,w.content,w.image_path,w.like_count,w.is_public,w.create_time FROM wei_yan w LEFT JOIN user u ON u.id=w.user_id WHERE w.id=?")
        .bind(id).fetch_one(&state.pool).await.map_err(db_error)?))
}
async fn delete_note(
    State(state): State<AppState>,
    Extension(member): Extension<Member>,
    Path(id): Path<i64>,
) -> ApiResult<serde_json::Value> {
    let changed = sqlx::query("DELETE FROM wei_yan WHERE id=? AND user_id=? AND type='friend'")
        .bind(id)
        .bind(member.id)
        .execute(&state.pool)
        .await
        .map_err(db_error)?;
    if changed.rows_affected() == 0 {
        return Err(absent());
    }
    Ok(Json(serde_json::json!({"deleted":true})))
}

#[derive(Serialize, sqlx::FromRow)]
struct TreeHole {
    id: i64,
    user_id: Option<i64>,
    username: Option<String>,
    avatar: Option<String>,
    message: String,
    image_path: Option<String>,
    create_time: Option<String>,
}
async fn tree_hole(State(state): State<AppState>) -> ApiResult<Vec<TreeHole>> {
    Ok(Json(
        sqlx::query_as::<_, TreeHole>(
            "SELECT t.id,t.user_id,u.username,t.avatar,t.message,t.image_path,t.create_time FROM tree_hole t LEFT JOIN user u ON u.id=t.user_id ORDER BY t.id DESC LIMIT 100",
        )
        .fetch_all(&state.pool)
        .await
        .map_err(db_error)?,
    ))
}
#[derive(Deserialize)]
struct TreeHoleInput {
    message: String,
    image_path: Option<String>,
}
async fn create_tree_hole(
    State(state): State<AppState>,
    Extension(member): Extension<Member>,
    Json(input): Json<TreeHoleInput>,
) -> ApiResult<TreeHole> {
    if (input.message.trim().is_empty() && input.image_path.is_none())
        || input.message.chars().count() > 64
        || input.message.contains('<')
        || input.message.contains('>')
    {
        return Err(invalid("留言无效"));
    }
    valid_member_image(&state, member.id, &input.image_path).await?;
    let now = crate::now_micros()
        .map_err(|_| AppError(StatusCode::INTERNAL_SERVER_ERROR, "时钟不可用"))?
        / 1_000_000;
    let count:i64=sqlx::query_scalar("INSERT INTO member_write_limits(user_id,kind,window_start,count) VALUES(?,'tree_hole',?,1) ON CONFLICT(user_id,kind) DO UPDATE SET count=CASE WHEN window_start<? THEN 1 ELSE count+1 END,window_start=CASE WHEN window_start<? THEN excluded.window_start ELSE window_start END RETURNING count")
        .bind(member.id).bind(now as i64).bind(now as i64-86400).bind(now as i64-86400).fetch_one(&state.pool).await.map_err(db_error)?;
    if count > 10 {
        return Err(AppError(StatusCode::TOO_MANY_REQUESTS, "今天留言太频繁了"));
    }
    let id = sqlx::query(
        "INSERT INTO tree_hole(user_id,avatar,message,image_path) VALUES(?,(SELECT avatar FROM user WHERE id=?),?,?)",
    )
    .bind(member.id)
    .bind(member.id)
    .bind(input.message)
    .bind(input.image_path)
    .execute(&state.pool)
    .await
    .map_err(db_error)?
    .last_insert_rowid();
    Ok(Json(
        sqlx::query_as(
            "SELECT t.id,t.user_id,u.username,t.avatar,t.message,t.image_path,t.create_time FROM tree_hole t LEFT JOIN user u ON u.id=t.user_id WHERE t.id=?",
        )
        .bind(id)
        .fetch_one(&state.pool)
        .await
        .map_err(db_error)?,
    ))
}
async fn create_guest_tree_hole(
    State(state): State<AppState>,
    ConnectInfo(peer): ConnectInfo<SocketAddr>,
    Json(input): Json<TreeHoleInput>,
) -> ApiResult<TreeHole> {
    let message = input.message.trim();
    if message.is_empty()
        || message.chars().count() > 60
        || message.contains('<')
        || message.contains('>')
        || input.image_path.is_some()
    {
        return Err(invalid("留言不能为空、不能超过 60 个字，也不能包含图片"));
    }
    let key = auth::attempt_key("guest_tree_hole", &peer.ip().to_string(), "");
    auth::record_attempt(&state.pool, &key, 5).await?;
    let id = sqlx::query("INSERT INTO tree_hole(message) VALUES(?)")
        .bind(message)
        .execute(&state.pool)
        .await
        .map_err(db_error)?
        .last_insert_rowid();
    Ok(Json(sqlx::query_as("SELECT t.id,t.user_id,u.username,t.avatar,t.message,t.image_path,t.create_time FROM tree_hole t LEFT JOIN user u ON u.id=t.user_id WHERE t.id=?")
        .bind(id).fetch_one(&state.pool).await.map_err(db_error)?))
}

#[derive(Serialize, sqlx::FromRow)]
struct Link {
    id: i64,
    title: Option<String>,
    classify: Option<String>,
    cover: Option<String>,
    url: Option<String>,
    introduction: Option<String>,
    link_type: Option<String>,
    create_time: Option<String>,
}
#[derive(Deserialize)]
struct LinkQuery {
    kind: Option<String>,
}
fn valid_link_kind(kind: &str) -> bool {
    matches!(kind, "favorites" | "friendUrl" | "lovePhoto" | "funny")
}
async fn links(
    State(state): State<AppState>,
    Query(query): Query<LinkQuery>,
) -> ApiResult<Vec<Link>> {
    let kind = query.kind.unwrap_or_else(|| "favorites".into());
    if !valid_link_kind(&kind) {
        return Err(invalid("资源类型无效"));
    }
    Ok(Json(sqlx::query_as::<_,Link>("SELECT id,title,classify,cover,url,introduction,type AS link_type,create_time FROM resource_path WHERE type=? AND status=1 ORDER BY id DESC LIMIT 200")
        .bind(kind).fetch_all(&state.pool).await.map_err(db_error)?))
}
#[derive(Deserialize)]
struct LinkPageQuery {
    kind: String,
    classify: Option<String>,
    page: Option<i64>,
    size: Option<i64>,
}
async fn link_page(
    State(state): State<AppState>,
    Query(query): Query<LinkPageQuery>,
) -> ApiResult<PageResult<Link>> {
    if !valid_link_kind(&query.kind) || query.classify.as_ref().is_some_and(|s| s.len() > 100) {
        return Err(invalid("资源筛选无效"));
    }
    let page = query.page.unwrap_or(1).clamp(1, 100_000);
    let size = query.size.unwrap_or(12).clamp(1, 100);
    let total: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM resource_path WHERE type=? AND status=1 AND (? IS NULL OR classify=?)")
        .bind(&query.kind).bind(&query.classify).bind(&query.classify)
        .fetch_one(&state.pool).await.map_err(db_error)?;
    let items=sqlx::query_as::<_,Link>("SELECT id,title,classify,cover,url,introduction,type AS link_type,create_time FROM resource_path WHERE type=? AND status=1 AND (? IS NULL OR classify=?) ORDER BY id DESC LIMIT ? OFFSET ?")
        .bind(&query.kind).bind(&query.classify).bind(&query.classify).bind(size).bind((page-1)*size)
        .fetch_all(&state.pool).await.map_err(db_error)?;
    Ok(Json(PageResult {
        items,
        total,
        page,
        size,
    }))
}
#[derive(Serialize, sqlx::FromRow)]
struct LinkClass {
    classify: String,
    count: i64,
}
async fn link_classes(
    State(state): State<AppState>,
    Query(query): Query<LinkQuery>,
) -> ApiResult<Vec<LinkClass>> {
    let kind = query.kind.unwrap_or_else(|| "lovePhoto".into());
    if !valid_link_kind(&kind) {
        return Err(invalid("资源类型无效"));
    }
    Ok(Json(sqlx::query_as::<_,LinkClass>("SELECT classify,COUNT(*) AS count FROM resource_path WHERE type=? AND status=1 AND classify IS NOT NULL AND classify<>'' GROUP BY classify ORDER BY MAX(id) DESC")
        .bind(kind).fetch_all(&state.pool).await.map_err(db_error)?))
}
#[derive(Deserialize)]
struct FriendLinkInput {
    title: String,
    introduction: String,
    cover: String,
    url: String,
}
fn valid_public_url(value: &str) -> bool {
    value.len() <= 2048
        && value.parse::<axum::http::Uri>().ok().is_some_and(|uri| {
            matches!(uri.scheme_str(), Some("http" | "https")) && uri.host().is_some()
        })
}
async fn submit_friend_link(
    State(state): State<AppState>,
    Extension(member): Extension<Member>,
    Json(input): Json<FriendLinkInput>,
) -> ApiResult<serde_json::Value> {
    if input.title.trim().is_empty()
        || input.title.chars().count() > 30
        || input.introduction.trim().is_empty()
        || input.introduction.chars().count() > 120
        || !valid_public_url(&input.cover)
        || !valid_public_url(&input.url)
    {
        return Err(invalid("请填写名称、简介以及有效的封面和网站地址"));
    }
    let now = crate::now_micros()
        .map_err(|_| AppError(StatusCode::INTERNAL_SERVER_ERROR, "时钟不可用"))?
        / 1_000_000;
    let count:i64=sqlx::query_scalar("INSERT INTO member_write_limits(user_id,kind,window_start,count) VALUES(?,'friend_link',?,1) ON CONFLICT(user_id,kind) DO UPDATE SET count=CASE WHEN window_start<? THEN 1 ELSE count+1 END,window_start=CASE WHEN window_start<? THEN excluded.window_start ELSE window_start END RETURNING count")
        .bind(member.id).bind(now as i64).bind(now as i64-86400).bind(now as i64-86400)
        .fetch_one(&state.pool).await.map_err(db_error)?;
    if count > 3 {
        return Err(AppError(
            StatusCode::TOO_MANY_REQUESTS,
            "今天提交得太频繁了",
        ));
    }
    let id=sqlx::query("INSERT INTO resource_path(title,classify,cover,url,introduction,type,status,remark) VALUES(?,'🥇友情链接',?,?,?,'friendUrl',0,?)")
        .bind(input.title.trim()).bind(input.cover).bind(input.url).bind(input.introduction.trim())
        .bind(format!("申请用户 #{}",member.id)).execute(&state.pool).await.map_err(db_error)?.last_insert_rowid();
    Ok(Json(serde_json::json!({"id":id,"pending":true})))
}

#[derive(Serialize, sqlx::FromRow)]
struct Family {
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
async fn family(State(state): State<AppState>) -> ApiResult<Vec<Family>> {
    Ok(Json(sqlx::query_as::<_,Family>("SELECT id,bg_cover,man_cover,woman_cover,man_name,woman_name,timing,countdown_title,countdown_time,family_info,like_count FROM family WHERE status=1 ORDER BY id DESC LIMIT 20")
        .fetch_all(&state.pool).await.map_err(db_error)?))
}

#[derive(Deserialize)]
struct FamilySubmission {
    man_name: String,
    woman_name: String,
    timing: String,
    bg_cover: Option<String>,
    man_cover: Option<String>,
    woman_cover: Option<String>,
    family_info: Option<String>,
}
fn valid_calendar_date(value: &str) -> bool {
    let bytes = value.as_bytes();
    if bytes.len() != 10
        || bytes[4] != b'-'
        || bytes[7] != b'-'
        || bytes
            .iter()
            .enumerate()
            .any(|(index, byte)| index != 4 && index != 7 && !byte.is_ascii_digit())
    {
        return false;
    }
    let year = value[..4].parse::<u32>().unwrap_or(0);
    let month = value[5..7].parse::<u32>().unwrap_or(0);
    let day = value[8..10].parse::<u32>().unwrap_or(0);
    if !(1900..=9999).contains(&year) || !(1..=12).contains(&month) {
        return false;
    }
    let leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0);
    let days = match month {
        2 if leap => 29,
        2 => 28,
        4 | 6 | 9 | 11 => 30,
        _ => 31,
    };
    (1..=days).contains(&day)
}
async fn submit_family(
    State(state): State<AppState>,
    Extension(member): Extension<Member>,
    Json(input): Json<FamilySubmission>,
) -> ApiResult<serde_json::Value> {
    let valid_image = |value: &Option<String>| {
        value.as_deref().is_none_or(|url| {
            url.is_empty()
                || (url.len() <= 2048 && (valid_public_url(url) || url.starts_with("/media/")))
        })
    };
    if input.man_name.trim().is_empty()
        || input.man_name.chars().count() > 32
        || input.woman_name.trim().is_empty()
        || input.woman_name.chars().count() > 32
        || !valid_calendar_date(&input.timing)
        || input
            .family_info
            .as_deref()
            .is_some_and(|value| value.chars().count() > 1024)
        || !valid_image(&input.bg_cover)
        || !valid_image(&input.man_cover)
        || !valid_image(&input.woman_cover)
    {
        return Err(invalid("请填写昵称、相识日期及有效的图片地址"));
    }
    let now = crate::now_micros()
        .map_err(|_| AppError(StatusCode::INTERNAL_SERVER_ERROR, "时钟不可用"))?
        / 1_000_000;
    let count: i64 = sqlx::query_scalar("INSERT INTO member_write_limits(user_id,kind,window_start,count) VALUES(?,'family_submission',?,1) ON CONFLICT(user_id,kind) DO UPDATE SET count=CASE WHEN window_start<? THEN 1 ELSE count+1 END,window_start=CASE WHEN window_start<? THEN excluded.window_start ELSE window_start END RETURNING count")
        .bind(member.id).bind(now as i64).bind(now as i64-86400).bind(now as i64-86400)
        .fetch_one(&state.pool).await.map_err(db_error)?;
    if count > 3 {
        return Err(AppError(
            StatusCode::TOO_MANY_REQUESTS,
            "今天提交得太频繁了",
        ));
    }
    let id = sqlx::query("INSERT INTO family(user_id,bg_cover,man_cover,woman_cover,man_name,woman_name,timing,family_info,status,like_count) VALUES(?,?,?,?,?,?,?,?,0,0)")
        .bind(member.id).bind(input.bg_cover.filter(|url| !url.is_empty()))
        .bind(input.man_cover.filter(|url| !url.is_empty()))
        .bind(input.woman_cover.filter(|url| !url.is_empty()))
        .bind(input.man_name.trim()).bind(input.woman_name.trim())
        .bind(input.timing).bind(input.family_info)
        .execute(&state.pool).await.map_err(db_error)?.last_insert_rowid();
    Ok(Json(serde_json::json!({"id":id,"pending":true})))
}

#[derive(Deserialize)]
struct CommentInput {
    article_id: i64,
    content: String,
    parent_comment_id: Option<i64>,
}

async fn create_comment(
    State(state): State<AppState>,
    Extension(member): Extension<Member>,
    Json(input): Json<CommentInput>,
) -> ApiResult<Comment> {
    if input.content.trim().is_empty()
        || input.content.chars().count() > 1024
        || input.content.contains('<')
        || input.content.contains('>')
    {
        return Err(invalid("评论内容无效"));
    }
    let article: Option<i64> = sqlx::query_scalar("SELECT comment_status FROM article WHERE id=? AND deleted=0 AND view_status=1 AND password IS NULL")
        .bind(input.article_id).fetch_optional(&state.pool).await.map_err(db_error)?;
    if article != Some(1) {
        return Err(AppError(StatusCode::FORBIDDEN, "文章不允许评论"));
    }
    let recent: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM comment WHERE user_id=? AND create_time>=datetime('now','-1 day')",
    )
    .bind(member.id)
    .fetch_one(&state.pool)
    .await
    .map_err(db_error)?;
    if recent >= 30 {
        return Err(AppError(
            StatusCode::TOO_MANY_REQUESTS,
            "今日评论次数已达上限",
        ));
    }
    let parent = input.parent_comment_id.unwrap_or(0);
    let (floor, parent_user) = if parent > 0 {
        let row: Option<(Option<i64>, Option<i64>, Option<i64>)> = sqlx::query_as("SELECT parent_comment_id,floor_comment_id,user_id FROM comment WHERE id=? AND source=? AND type='article'")
            .bind(parent).bind(input.article_id).fetch_optional(&state.pool).await.map_err(db_error)?;
        let Some((parent_parent, parent_floor, user)) = row else {
            return Err(invalid("回复目标不存在"));
        };
        (
            Some(if parent_parent.unwrap_or(0) == 0 {
                parent
            } else {
                parent_floor.unwrap_or(parent)
            }),
            user,
        )
    } else {
        (None, None)
    };
    let id = sqlx::query("INSERT INTO comment(source,type,parent_comment_id,user_id,floor_comment_id,parent_user_id,comment_content) VALUES(?,'article',?,?,?,?,?)")
        .bind(input.article_id).bind(parent).bind(member.id).bind(floor).bind(parent_user).bind(input.content)
        .execute(&state.pool).await.map_err(db_error)?.last_insert_rowid();
    Ok(Json(
        sqlx::query_as::<_, Comment>(&format!("{COMMENT_SQL} WHERE c.id=?"))
            .bind(id)
            .fetch_one(&state.pool)
            .await
            .map_err(db_error)?,
    ))
}

async fn delete_comment(
    State(state): State<AppState>,
    Extension(member): Extension<Member>,
    Path(id): Path<i64>,
) -> ApiResult<serde_json::Value> {
    let row: Option<(Option<i64>, Option<i64>)> = sqlx::query_as(
        "SELECT user_id,parent_comment_id FROM comment WHERE id=? AND type='article'",
    )
    .bind(id)
    .fetch_optional(&state.pool)
    .await
    .map_err(db_error)?;
    let Some((user_id, parent)) = row else {
        return Err(absent());
    };
    if user_id != Some(member.id) {
        return Err(AppError(StatusCode::FORBIDDEN, "无法删除其他人的评论"));
    }
    if parent.unwrap_or(0) == 0 {
        sqlx::query("DELETE FROM comment WHERE id=? OR floor_comment_id=?")
            .bind(id)
            .bind(id)
            .execute(&state.pool)
            .await
            .map_err(db_error)?;
    } else {
        sqlx::query("DELETE FROM comment WHERE id=?")
            .bind(id)
            .execute(&state.pool)
            .await
            .map_err(db_error)?;
    }
    Ok(Json(serde_json::json!({"deleted": true})))
}

#[derive(Deserialize)]
struct MessageCommentInput {
    content: String,
    parent_comment_id: Option<i64>,
}
async fn create_message_comment(
    State(state): State<AppState>,
    Extension(member): Extension<Member>,
    Json(input): Json<MessageCommentInput>,
) -> ApiResult<Comment> {
    if input.content.trim().is_empty()
        || input.content.chars().count() > 1024
        || input.content.contains('<')
        || input.content.contains('>')
    {
        return Err(invalid("评论内容无效"));
    }
    let recent: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM comment WHERE user_id=? AND create_time>=datetime('now','-1 day')",
    )
    .bind(member.id)
    .fetch_one(&state.pool)
    .await
    .map_err(db_error)?;
    if recent >= 30 {
        return Err(AppError(
            StatusCode::TOO_MANY_REQUESTS,
            "今日评论次数已达上限",
        ));
    }
    let parent = input.parent_comment_id.unwrap_or(0);
    let (floor, parent_user) = if parent > 0 {
        let row:Option<(Option<i64>,Option<i64>,Option<i64>)>=sqlx::query_as("SELECT parent_comment_id,floor_comment_id,user_id FROM comment WHERE id=? AND source=0 AND type='message'")
            .bind(parent).fetch_optional(&state.pool).await.map_err(db_error)?;
        let Some((parent_parent, parent_floor, user)) = row else {
            return Err(invalid("回复目标不存在"));
        };
        (
            Some(if parent_parent.unwrap_or(0) == 0 {
                parent
            } else {
                parent_floor.unwrap_or(parent)
            }),
            user,
        )
    } else {
        (None, None)
    };
    let id=sqlx::query("INSERT INTO comment(source,type,parent_comment_id,user_id,floor_comment_id,parent_user_id,comment_content) VALUES(0,'message',?,?,?,?,?)")
        .bind(parent).bind(member.id).bind(floor).bind(parent_user).bind(input.content.trim())
        .execute(&state.pool).await.map_err(db_error)?.last_insert_rowid();
    Ok(Json(
        sqlx::query_as::<_, Comment>(&format!("{COMMENT_SQL} WHERE c.id=?"))
            .bind(id)
            .fetch_one(&state.pool)
            .await
            .map_err(db_error)?,
    ))
}
async fn delete_message_comment(
    State(state): State<AppState>,
    Extension(member): Extension<Member>,
    Path(id): Path<i64>,
) -> ApiResult<serde_json::Value> {
    let row: Option<(Option<i64>, Option<i64>)> = sqlx::query_as(
        "SELECT user_id,parent_comment_id FROM comment WHERE id=? AND source=0 AND type='message'",
    )
    .bind(id)
    .fetch_optional(&state.pool)
    .await
    .map_err(db_error)?;
    let Some((user_id, parent)) = row else {
        return Err(absent());
    };
    if user_id != Some(member.id) {
        return Err(AppError(StatusCode::FORBIDDEN, "无法删除其他人的评论"));
    }
    if parent.unwrap_or(0) == 0 {
        sqlx::query("DELETE FROM comment WHERE id=? OR (floor_comment_id=? AND source=0 AND type='message')")
            .bind(id).bind(id).execute(&state.pool).await.map_err(db_error)?;
    } else {
        sqlx::query("DELETE FROM comment WHERE id=?")
            .bind(id)
            .execute(&state.pool)
            .await
            .map_err(db_error)?;
    }
    Ok(Json(serde_json::json!({"deleted":true})))
}
async fn create_love_comment(
    State(state): State<AppState>,
    Extension(member): Extension<Member>,
    Json(input): Json<MessageCommentInput>,
) -> ApiResult<Comment> {
    if input.content.trim().is_empty()
        || input.content.chars().count() > 1024
        || input.content.contains('<')
        || input.content.contains('>')
    {
        return Err(invalid("评论内容无效"));
    }
    let recent: i64 = sqlx::query_scalar(
        "SELECT COUNT(*) FROM comment WHERE user_id=? AND create_time>=datetime('now','-1 day')",
    )
    .bind(member.id)
    .fetch_one(&state.pool)
    .await
    .map_err(db_error)?;
    if recent >= 30 {
        return Err(AppError(
            StatusCode::TOO_MANY_REQUESTS,
            "今日评论次数已达上限",
        ));
    }
    let source = crate::site_author_id(&state.pool).await?;
    let parent = input.parent_comment_id.unwrap_or(0);
    let (floor, parent_user) = if parent > 0 {
        let row:Option<(Option<i64>,Option<i64>,Option<i64>)>=sqlx::query_as("SELECT parent_comment_id,floor_comment_id,user_id FROM comment WHERE id=? AND source=? AND type='love'")
            .bind(parent).bind(source).fetch_optional(&state.pool).await.map_err(db_error)?;
        let Some((parent_parent, parent_floor, user)) = row else {
            return Err(invalid("回复目标不存在"));
        };
        (
            Some(if parent_parent.unwrap_or(0) == 0 {
                parent
            } else {
                parent_floor.unwrap_or(parent)
            }),
            user,
        )
    } else {
        (None, None)
    };
    let id=sqlx::query("INSERT INTO comment(source,type,parent_comment_id,user_id,floor_comment_id,parent_user_id,comment_content) VALUES(?,'love',?,?,?,?,?)")
        .bind(source).bind(parent).bind(member.id).bind(floor).bind(parent_user).bind(input.content.trim())
        .execute(&state.pool).await.map_err(db_error)?.last_insert_rowid();
    Ok(Json(
        sqlx::query_as::<_, Comment>(&format!("{COMMENT_SQL} WHERE c.id=?"))
            .bind(id)
            .fetch_one(&state.pool)
            .await
            .map_err(db_error)?,
    ))
}
async fn delete_love_comment(
    State(state): State<AppState>,
    Extension(member): Extension<Member>,
    Path(id): Path<i64>,
) -> ApiResult<serde_json::Value> {
    let source = crate::site_author_id(&state.pool).await?;
    let row: Option<(Option<i64>, Option<i64>)> = sqlx::query_as(
        "SELECT user_id,parent_comment_id FROM comment WHERE id=? AND source=? AND type='love'",
    )
    .bind(id)
    .bind(source)
    .fetch_optional(&state.pool)
    .await
    .map_err(db_error)?;
    let Some((user_id, parent)) = row else {
        return Err(absent());
    };
    if user_id != Some(member.id) {
        return Err(AppError(StatusCode::FORBIDDEN, "无法删除其他人的评论"));
    }
    if parent.unwrap_or(0) == 0 {
        sqlx::query(
            "DELETE FROM comment WHERE (id=? OR floor_comment_id=?) AND source=? AND type='love'",
        )
        .bind(id)
        .bind(id)
        .bind(source)
        .execute(&state.pool)
        .await
        .map_err(db_error)?;
    } else {
        sqlx::query("DELETE FROM comment WHERE id=?")
            .bind(id)
            .execute(&state.pool)
            .await
            .map_err(db_error)?;
    }
    Ok(Json(serde_json::json!({"deleted":true})))
}
