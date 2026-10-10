use sha2::{Digest, Sha256};
use xcss::contracts::{
    ReleaseIdentity, StateContract, StateResource, StateResourceKind, StateSchemaIdentity,
};

pub fn state_contract_bytes() -> anyhow::Result<Vec<u8>> {
    let schema = crate::schema::current_identity()?;
    let contract = StateContract {
        contract_version: xcss::contracts::STATE_CONTRACT_VERSION,
        application: crate::PRODUCT_ID.into(),
        application_version: env!("CARGO_PKG_VERSION").into(),
        source_revision: env!("XOCS_SOURCE_REVISION").into(),
        schema: Some(StateSchemaIdentity {
            revision: schema.schema_revision,
            sha256: schema.schema_sha256,
        }),
        maintenance_locks: vec![
            ".xcss-instance.lock".into(),
            ".xcss-maintenance.lock".into(),
        ],
        resources: vec![
            StateResource {
                name: "state".into(),
                kind: StateResourceKind::DataTree,
                required: true,
            },
            StateResource {
                name: "media".into(),
                kind: StateResourceKind::DataTree,
                required: true,
            },
            StateResource {
                name: "config".into(),
                kind: StateResourceKind::Configuration,
                required: false,
            },
        ],
        external_requirements: vec![],
        companion_contracts: vec![],
    };
    contract.validate().map_err(|_| {
        crate::CliFailure(xcss::server_cli::ErrorEnvelope::with_code(
            xcss::server_cli::ErrorCode::new("release_identity_unbound").expect("static code"),
            "This development binary is not bound to a complete source revision.",
        ))
    })?;
    Ok(serde_json::to_vec(&contract)?)
}

pub fn identity() -> anyhow::Result<ReleaseIdentity> {
    let identity = ReleaseIdentity {
        product: crate::PRODUCT_ID.into(),
        version: env!("CARGO_PKG_VERSION").into(),
        source_revision: env!("XOCS_SOURCE_REVISION").into(),
        target: env!("XOCS_BUILD_TARGET").into(),
        state_contract_sha256: hex::encode(Sha256::digest(state_contract_bytes()?)),
    };
    identity.validate()?;
    Ok(identity)
}
