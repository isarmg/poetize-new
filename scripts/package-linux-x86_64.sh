#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <version>" >&2
  exit 2
fi

release_version="${1#v}"
if [[ ! "${release_version}" =~ ^[0-9A-Za-z][0-9A-Za-z._-]*$ ]]; then
  echo "Invalid release version: $1" >&2
  exit 2
fi

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_dir="$(cd -- "${script_dir}/.." && pwd)"
dist_dir="${project_dir}/dist"
package_name="poetize-${release_version}-linux-x86_64"
package_dir="${dist_dir}/${package_name}"
archive="${dist_dir}/${package_name}.tar.gz"
checksum="${archive}.sha256"
server_jar="${project_dir}/poetize-server/poetry-web/target/poetize-server.jar"
blog_dist="${project_dir}/poetize-ui/dist"
im_dist="${project_dir}/poetize-im-ui/dist"

for required_path in "${server_jar}" "${blog_dist}/index.html" "${im_dist}/index.html"; do
  if [[ ! -e "${required_path}" ]]; then
    echo "Missing build artifact: ${required_path}" >&2
    echo "Build both frontends and the backend before packaging." >&2
    exit 1
  fi
done

mkdir -p "${dist_dir}"
rm -rf -- "${package_dir}"
rm -f -- "${archive}" "${checksum}"
mkdir -p "${package_dir}/bin" "${package_dir}/config" "${package_dir}/sql" \
  "${package_dir}/web/blog" "${package_dir}/web/im"

install -m 0644 "${server_jar}" "${package_dir}/bin/poetize-server.jar"
install -m 0755 "${project_dir}/deploy/linux-x86_64/poetize-server" \
  "${package_dir}/bin/poetize-server"
install -m 0644 "${project_dir}/deploy/linux-x86_64/poetize.env.example" \
  "${package_dir}/config/poetize.env.example"
install -m 0644 "${project_dir}/deploy/linux-x86_64/nginx.conf.example" \
  "${package_dir}/config/nginx.conf.example"
install -m 0644 "${project_dir}/deploy/linux-x86_64/poetize.service" \
  "${package_dir}/config/poetize.service"
install -m 0644 "${project_dir}/deploy/linux-x86_64/README.md" \
  "${package_dir}/README.md"
install -m 0644 "${project_dir}/poetize-server/sql/poetry.sql" \
  "${package_dir}/sql/poetry.sql"
install -m 0644 "${project_dir}/poetize-server/LICENSE" "${package_dir}/LICENSE"
cp -a "${blog_dist}/." "${package_dir}/web/blog/"
cp -a "${im_dist}/." "${package_dir}/web/im/"
printf '%s\n' "${release_version}" > "${package_dir}/VERSION"

tar --create --gzip --file "${archive}" --directory "${dist_dir}" "${package_name}"
(
  cd -- "${dist_dir}"
  sha256sum "${package_name}.tar.gz" > "${package_name}.tar.gz.sha256"
)

echo "Created ${archive}"
echo "Created ${checksum}"
