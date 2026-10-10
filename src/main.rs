mod admin;
mod config;
mod content;
mod home;
mod lifecycle;
mod media;
mod rate_limit;
mod release;
mod schema;
mod static_assets;

mod app;
mod cli;
mod database;

pub(crate) use app::{
    ApiResult, AppError, AppState, absent, db_error, invalid, now_micros, site_author_id,
};
use xcss::server_cli::CliError as CliFailure;
pub use xcss::server_cli::ContractJson;

const PRODUCT_ID: &str = "xocs";

#[tokio::main]
async fn main() -> std::process::ExitCode {
    cli::run().await
}

#[cfg(test)]
mod tests;
