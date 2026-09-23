#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 || ! "${1}" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "Usage: $0 v<major>.<minor>.<patch>" >&2
  exit 2
fi

release_version="${1#v}"
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
dist_dir="${project_dir}/dist"
package_name="poetize-${release_version}-linux-x86_64"
package_dir="${dist_dir}/${package_name}"
archive="${dist_dir}/${package_name}.tar.gz"
binary="${project_dir}/poetize-rs/target/release/poetize-rs"
web="${project_dir}/poetize-web/dist"

if [[ ! -x "${binary}" || ! -f "${web}/index.html" ]]; then
  echo 'Build poetize-rs in release mode and poetize-web before packaging.' >&2
  exit 1
fi

mkdir -p "${dist_dir}"
rm -rf -- "${package_dir}"
rm -f -- "${archive}" "${archive}.sha256"
mkdir -p "${package_dir}/bin" "${package_dir}/web"
install -m 0755 "${binary}" "${package_dir}/bin/poetize-rs"
cp -a "${web}/." "${package_dir}/web/"
install -m 0644 "${project_dir}/deploy/linux-x86_64/README.md" "${package_dir}/README.md"
install -m 0644 "${project_dir}/LICENSE" "${package_dir}/LICENSE"
printf '%s\n' "${release_version}" > "${package_dir}/VERSION"

tar --create --gzip --file "${archive}" --directory "${dist_dir}" "${package_name}"
(
  cd -- "${dist_dir}"
  sha256sum "${package_name}.tar.gz" > "${package_name}.tar.gz.sha256"
)

echo "Created ${archive}"
echo "Created ${archive}.sha256"
