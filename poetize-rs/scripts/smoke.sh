#!/usr/bin/env bash
set -euo pipefail

project_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
binary="$project_root/poetize-rs/target/debug/poetize-rs"
web="$project_root/poetize-web/dist"
smoke_dir=$(mktemp -d /tmp/poetize-smoke.XXXXXX)
server_pid=''
cleanup() {
  if [[ -n "$server_pid" ]]; then kill "$server_pid" 2>/dev/null || true; wait "$server_pid" 2>/dev/null || true; fi
  rm -rf -- "$smoke_dir"
}
trap cleanup EXIT
chmod 700 "$smoke_dir"
mkdir -m 700 "$smoke_dir/media"
printf '%s\n' 'TemporaryPassphrase123' | "$binary" init --database "$smoke_dir/site.sqlite" >/dev/null
"$binary" serve --database "$smoke_dir/site.sqlite" --web "$web" --media "$smoke_dir/media" --development-http --bind 127.0.0.1:18881 >"$smoke_dir/server.log" 2>&1 &
server_pid=$!
for _ in {1..20}; do
  if curl -fsS http://127.0.0.1:18881/api/v2/health >/dev/null 2>&1; then break; fi
  if ! kill -0 "$server_pid" 2>/dev/null; then cat "$smoke_dir/server.log"; exit 1; fi
  sleep .25
done
test "$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:18881/api/v2/health)" = 200
test "$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:18881/api/v2/site)" = 200
test "$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:18881/api/v2/content/users)" = 401
test "$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:18881/api/v2/unknown)" = 404
test "$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:18881/admin)" = 200
curl -fsS -c "$smoke_dir/cookies" -o "$smoke_dir/login.json" \
  -H 'Origin: http://127.0.0.1:18881' -H 'Sec-Fetch-Site: same-origin' -H 'Content-Type: application/json' \
  --data '{"username":"admin","password":"TemporaryPassphrase123"}' \
  http://127.0.0.1:18881/api/v2/auth/login
csrf=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["csrf_token"])' < "$smoke_dir/login.json")
test "$(curl -s -o /dev/null -w '%{http_code}' -b "$smoke_dir/cookies" \
  -H 'Origin: http://127.0.0.1:18881' -H 'Sec-Fetch-Site: same-origin' -H "X-CSRF-Token: $csrf" -H 'Content-Type: application/json' \
  --data '{"name":"Smoke"}' http://127.0.0.1:18881/api/v2/content/categories)" = 200
test "$(curl -s -o /dev/null -w '%{http_code}' -b "$smoke_dir/cookies" \
  -H 'Origin: http://127.0.0.1:18881' -H 'Sec-Fetch-Site: same-origin' -H "X-CSRF-Token: $csrf" -H 'Content-Type: application/json' \
  --data '{"sort_id":1,"name":"Smoke"}' http://127.0.0.1:18881/api/v2/content/labels)" = 200
test "$(curl -s -o /dev/null -w '%{http_code}' -X PUT -b "$smoke_dir/cookies" \
  -H 'Origin: http://127.0.0.1:18881' -H 'Sec-Fetch-Site: same-origin' -H "X-CSRF-Token: $csrf" -H 'Content-Type: application/json' \
  --data '[{"title":"最新","kind":"latest","sort_id":null,"priority":0,"enabled":true},{"title":"推荐阅读","kind":"recommended","sort_id":null,"priority":10,"enabled":true},{"title":"专栏速览","kind":"category","sort_id":1,"priority":20,"enabled":true}]' \
  http://127.0.0.1:18881/api/v2/content/home-sections)" = 200
test "$(curl -s -o /dev/null -w '%{http_code}' -b "$smoke_dir/cookies" \
  -H 'Origin: http://127.0.0.1:18881' -H 'Sec-Fetch-Site: same-origin' -H "X-CSRF-Token: $csrf" -H 'Content-Type: application/json' \
  --data '{"article_title":"Smoke","article_content":"## Chapter One\nTest body","article_cover":null,"video_url":null,"sort_id":1,"label_id":1,"view_status":true,"recommend_status":true,"comment_status":true,"password":null,"tips":null}' \
  http://127.0.0.1:18881/api/v2/content/articles)" = 200
test "$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:18881/api/v2/articles/1)" = 200
test "$(curl -s -o /dev/null -w '%{http_code}' -b "$smoke_dir/cookies" \
  -H 'Origin: http://127.0.0.1:18881' -H 'Sec-Fetch-Site: same-origin' -H "X-CSRF-Token: $csrf" -H 'Content-Type: application/json' \
  --data '{"content":"Smoke article news","create_time":null}' \
  http://127.0.0.1:18881/api/v2/content/articles/1/news)" = 200
curl -fsS http://127.0.0.1:18881/api/v2/articles/1/news | grep -q 'Smoke article news'
test "$(curl -s -o /dev/null -w '%{http_code}' -b "$smoke_dir/cookies" \
  -H 'Origin: http://127.0.0.1:18881' -H 'Sec-Fetch-Site: same-origin' -H "X-CSRF-Token: $csrf" -H 'Content-Type: application/json' \
  --data '{"content":"Smoke author note","is_public":true}' \
  http://127.0.0.1:18881/api/v2/content/notes)" = 200
curl -fsS 'http://127.0.0.1:18881/api/v2/notes/page?page=1' | grep -q 'Smoke author note'
test "$(curl -s -o /dev/null -w '%{http_code}' -b "$smoke_dir/cookies" \
  -H 'Origin: http://127.0.0.1:18881' -H 'Sec-Fetch-Site: same-origin' -H "X-CSRF-Token: $csrf" -H 'Content-Type: application/json' \
  --data '{"article_title":"Protected","article_content":"Secret body","article_cover":null,"video_url":null,"sort_id":1,"label_id":1,"view_status":false,"recommend_status":false,"comment_status":true,"password":"ProtectedPassphrase123","tips":null}' \
  http://127.0.0.1:18881/api/v2/content/articles)" = 200
test "$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:18881/api/v2/articles/2)" = 404
test "$(curl -s -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' \
  --data '{"password":"ProtectedPassphrase123"}' http://127.0.0.1:18881/api/v2/articles/2/unlock)" = 200
test "$(curl -s -o /dev/null -w '%{http_code}' -X PUT -b "$smoke_dir/cookies" \
  -H 'Origin: http://127.0.0.1:18881' -H 'Sec-Fetch-Site: same-origin' -H "X-CSRF-Token: $csrf" -H 'Content-Type: application/json' \
  --data '{"article_title":"Published","article_content":"Public body","article_cover":null,"video_url":null,"sort_id":1,"label_id":1,"view_status":true,"recommend_status":false,"comment_status":true,"password":null,"tips":null}' \
  http://127.0.0.1:18881/api/v2/content/articles/2)" = 200
test "$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:18881/api/v2/articles/2)" = 200
test "$(curl -s -o /dev/null -w '%{http_code}' -b "$smoke_dir/cookies" \
  -H 'Origin: http://127.0.0.1:18881' -H 'Sec-Fetch-Site: same-origin' -H "X-CSRF-Token: $csrf" -H 'Content-Type: application/json' \
  --data '{"article_title":"Protected Preview","article_content":"Unlocked body","article_cover":null,"video_url":null,"sort_id":1,"label_id":1,"view_status":false,"recommend_status":false,"comment_status":true,"password":"ProtectedPassphrase123","tips":"Ask the author"}' \
  http://127.0.0.1:18881/api/v2/content/articles)" = 200
test "$(curl -s -o /dev/null -w '%{http_code}' -b "$smoke_dir/cookies" \
  -H 'Origin: http://127.0.0.1:18881' -H 'Sec-Fetch-Site: same-origin' -H "X-CSRF-Token: $csrf" -H 'Content-Type: application/json' \
  --data '{"man_name":"A","woman_name":"B","timing":"2020-01-01","status":true}' \
  http://127.0.0.1:18881/api/v2/content/family)" = 200
test "$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:18881/api/v2/family)" = 200
printf '%s' 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/8ZkAAAAASUVORK5CYII=' | base64 -d > "$smoke_dir/image.png"
test "$(curl -s -o /dev/null -w '%{http_code}' -b "$smoke_dir/cookies" \
  -H 'Origin: http://127.0.0.1:18881' -H 'Sec-Fetch-Site: same-origin' -H "X-CSRF-Token: $csrf" -H 'Content-Type: image/png' \
  --data-binary @"$smoke_dir/image.png" http://127.0.0.1:18881/api/v2/content/upload)" = 200
curl -fsS -c "$smoke_dir/member-cookies" -o "$smoke_dir/member.json" \
  -H 'Origin: http://127.0.0.1:18881' -H 'Sec-Fetch-Site: same-origin' -H 'Content-Type: application/json' \
  --data '{"username":"smokemember","password":"MemberPassphrase123"}' \
  http://127.0.0.1:18881/api/v2/members/register
member_csrf=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["csrf_token"])' < "$smoke_dir/member.json")
curl -fsS -c "$smoke_dir/friend-cookies" -o "$smoke_dir/friend.json" \
  -H 'Origin: http://127.0.0.1:18881' -H 'Sec-Fetch-Site: same-origin' -H 'Content-Type: application/json' \
  --data '{"username":"smokefriend","password":"FriendPassphrase123"}' \
  http://127.0.0.1:18881/api/v2/members/register
friend_csrf=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["csrf_token"])' < "$smoke_dir/friend.json")
friend_id=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])' < "$smoke_dir/friend.json")
member_id=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])' < "$smoke_dir/member.json")
test "$(curl -s -o /dev/null -w '%{http_code}' -b "$smoke_dir/member-cookies" \
  -H 'Origin: http://127.0.0.1:18881' -H 'Sec-Fetch-Site: same-origin' -H "X-CSRF-Token: $member_csrf" \
  -X POST "http://127.0.0.1:18881/api/v2/im/friends/$friend_id/request")" = 200
test "$(curl -s -o /dev/null -w '%{http_code}' -b "$smoke_dir/friend-cookies" \
  -H 'Origin: http://127.0.0.1:18881' -H 'Sec-Fetch-Site: same-origin' -H "X-CSRF-Token: $friend_csrf" \
  -X POST "http://127.0.0.1:18881/api/v2/im/friends/$member_id/accept")" = 200
test "$(curl -s -o /dev/null -w '%{http_code}' -X PUT -b "$smoke_dir/member-cookies" \
  -H 'Origin: http://127.0.0.1:18881' -H 'Sec-Fetch-Site: same-origin' -H "X-CSRF-Token: $member_csrf" -H 'Content-Type: application/json' \
  --data '{"remark":"Test friend"}' "http://127.0.0.1:18881/api/v2/im/friends/$friend_id/remark")" = 200
curl -fsS -b "$smoke_dir/member-cookies" http://127.0.0.1:18881/api/v2/im/friends | grep -q 'Test friend'
test "$(curl -s -o /dev/null -w '%{http_code}' -X DELETE -b "$smoke_dir/member-cookies" \
  -H 'Origin: http://127.0.0.1:18881' -H 'Sec-Fetch-Site: same-origin' -H "X-CSRF-Token: $member_csrf" \
  "http://127.0.0.1:18881/api/v2/im/friends/$friend_id")" = 200
test "$(curl -s -o /dev/null -w '%{http_code}' -b "$smoke_dir/member-cookies" \
  -H 'Origin: http://127.0.0.1:18881' -H 'Sec-Fetch-Site: same-origin' -H "X-CSRF-Token: $member_csrf" -H 'Content-Type: application/json' \
  --data '{"content":"Smoke love wish"}' http://127.0.0.1:18881/api/v2/love-comments)" = 200
curl -fsS http://127.0.0.1:18881/api/v2/love-comments | grep -q 'Smoke love wish'
test "$(curl -s -o /dev/null -w '%{http_code}' -b "$smoke_dir/member-cookies" \
  -H 'Origin: http://127.0.0.1:18881' -H 'Sec-Fetch-Site: same-origin' -H "X-CSRF-Token: $member_csrf" -H 'Content-Type: application/json' \
  --data '{"content":"invalid image","image_path":"/media/not-uploaded.png"}' http://127.0.0.1:18881/api/v2/notes)" = 400
curl -fsS -b "$smoke_dir/member-cookies" -o "$smoke_dir/member-upload.json" \
  -H 'Origin: http://127.0.0.1:18881' -H 'Sec-Fetch-Site: same-origin' -H "X-CSRF-Token: $member_csrf" -H 'Content-Type: image/png' \
  --data-binary @"$smoke_dir/image.png" http://127.0.0.1:18881/api/v2/members/upload
image_path=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["path"])' < "$smoke_dir/member-upload.json")
python3 -c 'import json,sys; print(json.dumps({"content":"图片动态","image_path":sys.argv[1]}))' "$image_path" > "$smoke_dir/note-payload.json"
python3 -c 'import json,sys; print(json.dumps({"message":"图片留言","image_path":sys.argv[1]}))' "$image_path" > "$smoke_dir/wall-payload.json"
test "$(curl -s -o /dev/null -w '%{http_code}' -b "$smoke_dir/member-cookies" \
  -H 'Origin: http://127.0.0.1:18881' -H 'Sec-Fetch-Site: same-origin' -H "X-CSRF-Token: $member_csrf" -H 'Content-Type: application/json' \
  --data @"$smoke_dir/note-payload.json" http://127.0.0.1:18881/api/v2/notes)" = 200
test "$(curl -s -o /dev/null -w '%{http_code}' -b "$smoke_dir/member-cookies" \
  -H 'Origin: http://127.0.0.1:18881' -H 'Sec-Fetch-Site: same-origin' -H "X-CSRF-Token: $member_csrf" -H 'Content-Type: application/json' \
  --data @"$smoke_dir/wall-payload.json" http://127.0.0.1:18881/api/v2/tree-hole)" = 200
test "$(curl -s -o /dev/null -w '%{http_code}' -b "$smoke_dir/cookies" \
  http://127.0.0.1:18881/api/v2/content/notes)" = 200
POETIZE_SMOKE_URL=http://127.0.0.1:18881 node "$project_root/poetize-web/tests/browser-smoke.mjs"
test "$(curl -s -o /dev/null -w '%{http_code}' -X DELETE http://127.0.0.1:18881/api/v2/content/notes/2)" = 401
test "$(curl -s -o /dev/null -w '%{http_code}' -X DELETE -b "$smoke_dir/cookies" \
  -H 'Origin: http://127.0.0.1:18881' -H 'Sec-Fetch-Site: same-origin' -H "X-CSRF-Token: $csrf" \
  http://127.0.0.1:18881/api/v2/content/notes/2)" = 200
test "$(curl -s -o /dev/null -w '%{http_code}' -X DELETE -b "$smoke_dir/cookies" \
  -H 'Origin: http://127.0.0.1:18881' -H 'Sec-Fetch-Site: same-origin' -H "X-CSRF-Token: $csrf" \
  http://127.0.0.1:18881/api/v2/content/tree-hole/1)" = 200
"$binary" doctor --database "$smoke_dir/site.sqlite" | grep -qx ok
echo 'server smoke: ok'
