use anyhow::{Context, ensure};
use clap::Args;
use serde::{Deserialize, Serialize};
use std::{
    net::SocketAddr,
    path::{Path, PathBuf},
};
use xcss_config::{EnvMapping, EnvValueKind, Loaded, Override};

#[derive(Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Settings {
    pub data_dir: Option<PathBuf>,
    pub database: Option<PathBuf>,
    pub media: Option<PathBuf>,
    pub bind: SocketAddr,
    pub development_http: bool,
    pub web: Option<PathBuf>,
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            data_dir: None,
            database: None,
            media: None,
            bind: "127.0.0.1:8081".parse().expect("static loopback address"),
            development_http: false,
            web: None,
        }
    }
}

#[derive(Args, Default)]
pub struct Overrides {
    #[arg(long)]
    pub database: Option<PathBuf>,
    #[arg(long)]
    pub media: Option<PathBuf>,
    #[arg(long)]
    pub bind: Option<SocketAddr>,
    /// Use insecure HTTP cookies only for an explicit loopback development server.
    #[arg(long, num_args=0..=1, default_missing_value="true")]
    pub development_http: Option<bool>,
    /// Explicit development-only directory override; production embeds its resources.
    #[arg(long)]
    pub web: Option<PathBuf>,
}

pub fn load(
    config: Option<&Path>,
    data_dir: Option<&Path>,
    args: &Overrides,
) -> anyhow::Result<Loaded<Settings>> {
    let bytes = config.map(xcss_config::read_private_file).transpose()?;
    let mappings = [
        EnvMapping {
            variable: "XOCS_DATA_DIR",
            path: "/data_dir",
            kind: EnvValueKind::String,
        },
        EnvMapping {
            variable: "XOCS_DATABASE",
            path: "/database",
            kind: EnvValueKind::String,
        },
        EnvMapping {
            variable: "XOCS_MEDIA",
            path: "/media",
            kind: EnvValueKind::String,
        },
        EnvMapping {
            variable: "XOCS_BIND",
            path: "/bind",
            kind: EnvValueKind::String,
        },
        EnvMapping {
            variable: "XOCS_DEVELOPMENT_HTTP",
            path: "/development_http",
            kind: EnvValueKind::Boolean,
        },
        EnvMapping {
            variable: "XOCS_DEV_WEB_DIR",
            path: "/web",
            kind: EnvValueKind::String,
        },
    ];
    let mut values = std::collections::BTreeMap::new();
    for mapping in &mappings {
        match std::env::var(mapping.variable) {
            Ok(value) => {
                values.insert(mapping.variable, value);
            }
            Err(std::env::VarError::NotPresent) => {}
            Err(_) => anyhow::bail!("{} must be valid Unicode", mapping.variable),
        }
    }
    let environment = xcss_config::read_environment(&mappings, |name| values.get(name).cloned())?;
    let mut cli = Vec::new();
    for (name, path) in [
        ("data_dir", data_dir),
        ("database", args.database.as_deref()),
        ("media", args.media.as_deref()),
        ("web", args.web.as_deref()),
    ] {
        if let Some(path) = path {
            cli.push(Override::new(
                format!("/{name}"),
                serde_json::to_value(path)?,
            ));
        }
    }
    if let Some(bind) = args.bind {
        cli.push(Override::new("/bind", bind.to_string()));
    }
    if let Some(value) = args.development_http {
        cli.push(Override::new("/development_http", value));
    }
    let settings = xcss_config::resolve_validated(
        &Settings::default(),
        bytes.as_deref(),
        &environment,
        &cli,
        |value, source| {
            value.validate().map_err(|_| {
                xcss_config::ConfigError::new(xcss_config::Reason::InvalidValue, "/", source)
            })
        },
    )?;
    settings.value.validate()?;
    if let (Some(config), Ok(media)) = (config, settings.value.media_path())
        && config.starts_with(media)
    {
        return Err(xcss_config::ConfigError::new(
            xcss_config::Reason::InvalidValue,
            "/media",
            xcss_config::ConfigSource::File,
        )
        .into());
    }
    Ok(settings)
}

impl Settings {
    pub fn database_path(&self) -> anyhow::Result<PathBuf> {
        self.database
            .clone()
            .or_else(|| self.data_dir.as_ref().map(|root| root.join("site.sqlite")))
            .context("--data-dir or a current database location is required")
    }
    pub fn media_path(&self) -> anyhow::Result<PathBuf> {
        self.media
            .clone()
            .or_else(|| self.data_dir.as_ref().map(|root| root.join("media")))
            .or_else(|| {
                self.database
                    .as_ref()
                    .and_then(|path| path.parent())
                    .map(|root| root.join("media"))
            })
            .context("--data-dir or a current media location is required")
    }
    pub fn state_directory(&self) -> anyhow::Result<PathBuf> {
        let database = self.database_path()?;
        let parent = database
            .parent()
            .context("database directory is required")?;
        if let Some(root) = &self.data_dir {
            ensure!(
                parent == root,
                "database must be a direct child of --data-dir for the shared maintenance protocol"
            );
        }
        Ok(parent.to_path_buf())
    }
    fn validate(&self) -> anyhow::Result<()> {
        for path in [&self.data_dir, &self.database, &self.media, &self.web]
            .into_iter()
            .flatten()
        {
            ensure!(path.is_absolute(), "configured paths must be absolute");
            ensure!(
                !path.components().any(|p| matches!(
                    p,
                    std::path::Component::ParentDir | std::path::Component::CurDir
                )),
                "configured paths must not contain traversal components"
            );
        }
        if let Some(media) = &self.media
            && let Some(database) = self
                .database
                .as_ref()
                .cloned()
                .or_else(|| self.data_dir.as_ref().map(|root| root.join("site.sqlite")))
        {
            ensure!(
                !database.starts_with(media)
                    && !database
                        .parent()
                        .is_some_and(|root| root.starts_with(media)),
                "media directory must not expose private application state"
            );
        }
        ensure!(
            !self.development_http || self.bind.ip().is_loopback(),
            "development HTTP may only bind loopback"
        );
        ensure!(
            self.web.is_none() || self.development_http,
            "Web directory overrides require development HTTP"
        );
        Ok(())
    }
}

pub fn runtime_lock(settings: &Settings) -> anyhow::Result<xcss_state_file::InstanceLock> {
    let state = xcss_state_file::PrivateStateDirectory::open(settings.state_directory()?)?;
    xcss_server_cli::runtime_allowed(state.path()).map_err(crate::CliFailure)?;
    let lock = state.try_instance_lock()?;
    xcss_server_cli::runtime_allowed(state.path()).map_err(crate::CliFailure)?;
    state.verify_identity()?;
    Ok(lock)
}
