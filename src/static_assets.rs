use axum::{extract::Request, response::Response};

include!(concat!(env!("OUT_DIR"), "/xcss-web-assets.rs"));

pub fn verify() -> anyhow::Result<()> {
    xcss_web_assets::verify_embedded(ASSETS, MANIFEST, DIGEST)
}

pub async fn serve(request: Request) -> Response {
    xcss_web_assets::response(
        ASSETS,
        request.uri().path(),
        request.method(),
        request.headers(),
    )
    .map(axum::body::Body::from)
}

pub async fn entry(request: Request) -> Response {
    xcss_web_assets::response(ASSETS, "/index.html", request.method(), request.headers())
        .map(axum::body::Body::from)
}
