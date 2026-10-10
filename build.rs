use std::{collections::BTreeSet, env, fs, path::PathBuf};

fn main() {
    println!("cargo:rerun-if-env-changed=XCSS_WEB_DIST");
    println!("cargo:rerun-if-env-changed=XOCS_SOURCE_REVISION");
    let root = PathBuf::from(env::var_os("CARGO_MANIFEST_DIR").expect("Cargo manifest directory"));
    let lock_path = root.join("Cargo.lock");
    println!("cargo:rerun-if-changed={}", lock_path.display());
    let lock = fs::read_to_string(lock_path).expect("read unique workspace Cargo.lock");
    let revisions = lock
        .lines()
        .filter_map(|line| {
            line.strip_prefix("source = \"git+https://github.com/isarmg/xcss.git?rev=")
        })
        .map(|line| {
            let (requested, actual) = line
                .trim_end_matches('"')
                .split_once('#')
                .expect("complete xcss source identity");
            assert_eq!(requested, actual, "xcss source must match its exact pin");
            assert!(
                actual.len() == 40
                    && actual
                        .bytes()
                        .all(|b| b.is_ascii_digit() || matches!(b, b'a'..=b'f')),
                "xcss source must be a full lowercase commit"
            );
            actual.to_owned()
        })
        .collect::<BTreeSet<_>>();
    assert_eq!(revisions.len(), 1, "one coherent xcss input is required");
    println!(
        "cargo:rustc-env=XCSS_REVISION={}",
        revisions.into_iter().next().expect("one xcss source")
    );
    let web = env::var_os("XCSS_WEB_DIST")
        .map(PathBuf::from)
        .unwrap_or_else(|| root.join("web/dist"));
    xcss::web_assets::build::generate(web).expect("build embedded Xocs Web assets");
    let target = env::var("TARGET").expect("Cargo build target");
    let revision = env::var("XOCS_SOURCE_REVISION").unwrap_or_else(|_| "unbound".into());
    assert!(
        revision == "unbound"
            || (revision.len() == 40
                && revision
                    .bytes()
                    .all(|b| b.is_ascii_digit() || matches!(b, b'a'..=b'f'))),
        "XOCS_SOURCE_REVISION must be unbound or a full lowercase Git commit"
    );
    println!("cargo:rustc-env=XOCS_BUILD_TARGET={target}");
    println!("cargo:rustc-env=XOCS_SOURCE_REVISION={revision}");
    println!("cargo:rerun-if-changed=schema/product.sql");
    let schema = format!(
        "{};\n{}\n{}",
        xcss::schema_identity::PRODUCT_METADATA_DDL,
        xcss::admin_sqlite::ADMIN_PERSISTENT_DDL,
        include_str!("schema/product.sql")
    );
    std::fs::write(
        PathBuf::from(env::var_os("OUT_DIR").expect("Cargo output directory"))
            .join("xocs-current-schema.sql"),
        &schema,
    )
    .expect("write current composed schema");
    // This is trusted DDL composed from owned source constants, never input.
    let fingerprint =
        xcss::sqlite::fingerprint_trusted_ddl(&[&schema]).expect("fingerprint current Xocs DDL");
    println!("cargo:rustc-env=XOCS_SCHEMA_SHA256={fingerprint}");
}
