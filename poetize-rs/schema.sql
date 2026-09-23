-- Schema for a new POETIZE installation.
PRAGMA foreign_keys = ON;

CREATE TABLE _sarmg_administrators (
    administrator_id TEXT PRIMARY KEY CHECK(length(administrator_id) BETWEEN 1 AND 64),
    username TEXT NOT NULL UNIQUE CHECK(length(username) BETWEEN 3 AND 64 AND username = lower(trim(username)) AND username NOT GLOB '*[^a-z0-9._-]*' AND substr(username,1,1) GLOB '[a-z0-9]' AND substr(username,-1,1) GLOB '[a-z0-9]'),
    password_hash TEXT NOT NULL CHECK(length(password_hash) > 0),
    active INTEGER NOT NULL DEFAULT 1 CHECK(active IN (0,1)),
    session_version INTEGER NOT NULL DEFAULT 1 CHECK(session_version > 0),
    created_at_micros INTEGER NOT NULL CHECK(created_at_micros >= 0),
    updated_at_micros INTEGER NOT NULL CHECK(updated_at_micros >= created_at_micros),
    last_login_at_micros INTEGER
);
CREATE TABLE _sarmg_admin_sessions (
    session_id TEXT PRIMARY KEY CHECK(length(session_id) BETWEEN 1 AND 64),
    administrator_id TEXT NOT NULL REFERENCES _sarmg_administrators(administrator_id) ON DELETE RESTRICT,
    token_hash BLOB NOT NULL UNIQUE CHECK(length(token_hash) = 32),
    csrf_hash BLOB NOT NULL CHECK(length(csrf_hash) = 32),
    administrator_session_version INTEGER NOT NULL CHECK(administrator_session_version > 0),
    created_at_micros INTEGER NOT NULL,
    last_seen_at_micros INTEGER NOT NULL,
    idle_expires_at_micros INTEGER NOT NULL,
    absolute_expires_at_micros INTEGER NOT NULL,
    revoked_at_micros INTEGER,
    CHECK(last_seen_at_micros >= created_at_micros),
    CHECK(idle_expires_at_micros > created_at_micros),
    CHECK(absolute_expires_at_micros >= idle_expires_at_micros)
);
CREATE INDEX _sarmg_admin_sessions_administrator_idx ON _sarmg_admin_sessions(administrator_id,revoked_at_micros);
CREATE INDEX _sarmg_admin_sessions_expiry_idx ON _sarmg_admin_sessions(idle_expires_at_micros,absolute_expires_at_micros) WHERE revoked_at_micros IS NULL;
CREATE TABLE _sarmg_security_audit_events (
    event_id TEXT PRIMARY KEY,
    action TEXT NOT NULL,
    outcome TEXT NOT NULL CHECK(outcome IN ('success','failure')),
    actor_administrator_id TEXT,
    subject_digest BLOB,
    request_id TEXT,
    detail_json TEXT NOT NULL DEFAULT '{}',
    occurred_at_micros INTEGER NOT NULL
);

CREATE TABLE user (
    id INTEGER PRIMARY KEY,
    username TEXT UNIQUE,
    password TEXT,
    phone_number TEXT UNIQUE,
    email TEXT UNIQUE,
    user_status INTEGER NOT NULL DEFAULT 1,
    gender INTEGER,
    open_id TEXT,
    avatar TEXT,
    admire TEXT,
    subscribe TEXT,
    introduction TEXT,
    user_type INTEGER NOT NULL DEFAULT 2,
    create_time TEXT DEFAULT CURRENT_TIMESTAMP,
    update_time TEXT DEFAULT CURRENT_TIMESTAMP,
    update_by TEXT,
    deleted INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE member_sessions (
    token_hash BLOB PRIMARY KEY CHECK(length(token_hash)=32),
    csrf_hash BLOB NOT NULL CHECK(length(csrf_hash)=32),
    user_id INTEGER NOT NULL REFERENCES user(id) ON DELETE CASCADE,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    CHECK(expires_at>created_at)
);
CREATE INDEX member_sessions_expiry ON member_sessions(expires_at);
CREATE TABLE member_login_failures (
    failure_key BLOB PRIMARY KEY CHECK(length(failure_key)=32),
    failures INTEGER NOT NULL DEFAULT 0,
    expires_at INTEGER NOT NULL
);
CREATE TABLE member_write_limits (
    user_id INTEGER NOT NULL REFERENCES user(id) ON DELETE CASCADE,
    kind TEXT NOT NULL,
    window_start INTEGER NOT NULL,
    count INTEGER NOT NULL,
    PRIMARY KEY(user_id,kind)
);
CREATE TABLE article (
    id INTEGER PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES user(id),
    sort_id INTEGER NOT NULL,
    label_id INTEGER NOT NULL,
    article_cover TEXT,
    article_title TEXT NOT NULL,
    article_content TEXT NOT NULL,
    video_url TEXT,
    view_count INTEGER NOT NULL DEFAULT 0,
    like_count INTEGER NOT NULL DEFAULT 0,
    view_status INTEGER NOT NULL DEFAULT 1,
    password TEXT,
    tips TEXT,
    recommend_status INTEGER NOT NULL DEFAULT 0,
    comment_status INTEGER NOT NULL DEFAULT 1,
    create_time TEXT DEFAULT CURRENT_TIMESTAMP,
    update_time TEXT DEFAULT CURRENT_TIMESTAMP,
    update_by TEXT,
    deleted INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX article_public_latest ON article(deleted,view_status,create_time DESC,id DESC);
CREATE INDEX article_sort ON article(sort_id,label_id,deleted,create_time DESC);
CREATE VIRTUAL TABLE article_search USING fts5(article_title,article_content,content='article',content_rowid='id',tokenize='trigram');
CREATE TRIGGER article_search_insert AFTER INSERT ON article BEGIN
    INSERT INTO article_search(rowid,article_title,article_content) VALUES(new.id,new.article_title,new.article_content);
END;
CREATE TRIGGER article_search_delete AFTER DELETE ON article BEGIN
    INSERT INTO article_search(article_search,rowid,article_title,article_content) VALUES('delete',old.id,old.article_title,old.article_content);
END;
CREATE TRIGGER article_search_update AFTER UPDATE OF article_title,article_content ON article BEGIN
    INSERT INTO article_search(article_search,rowid,article_title,article_content) VALUES('delete',old.id,old.article_title,old.article_content);
    INSERT INTO article_search(rowid,article_title,article_content) VALUES(new.id,new.article_title,new.article_content);
END;
CREATE TABLE comment (
    id INTEGER PRIMARY KEY,
    source INTEGER NOT NULL,
    type TEXT,
    parent_comment_id INTEGER,
    user_id INTEGER,
    floor_comment_id INTEGER,
    parent_user_id INTEGER,
    like_count INTEGER NOT NULL DEFAULT 0,
    comment_content TEXT NOT NULL,
    comment_info TEXT,
    create_time TEXT DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX comment_source ON comment(source,type,create_time,id);
CREATE TABLE sort (id INTEGER PRIMARY KEY,sort_name TEXT NOT NULL,sort_description TEXT,sort_type INTEGER,priority INTEGER);
CREATE TABLE home_sections (
    id INTEGER PRIMARY KEY,
    title TEXT NOT NULL CHECK(length(title) BETWEEN 1 AND 32),
    kind TEXT NOT NULL CHECK(kind IN ('latest','recommended','category')),
    sort_id INTEGER REFERENCES sort(id) ON DELETE CASCADE,
    priority INTEGER NOT NULL DEFAULT 0,
    enabled INTEGER NOT NULL DEFAULT 1 CHECK(enabled IN (0,1)),
    CHECK((kind='category' AND sort_id IS NOT NULL) OR (kind!='category' AND sort_id IS NULL))
);
CREATE INDEX home_sections_visible ON home_sections(enabled,priority,id);
INSERT INTO home_sections(title,kind,priority) VALUES('最新', 'latest', 0),('推荐阅读', 'recommended', 10);
CREATE TABLE label (id INTEGER PRIMARY KEY,sort_id INTEGER NOT NULL,label_name TEXT NOT NULL,label_description TEXT);
CREATE TABLE tree_hole (id INTEGER PRIMARY KEY,avatar TEXT,message TEXT,image_path TEXT REFERENCES resource(path),user_id INTEGER REFERENCES user(id),create_time TEXT DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE wei_yan (id INTEGER PRIMARY KEY,user_id INTEGER,like_count INTEGER NOT NULL DEFAULT 0,content TEXT,image_path TEXT REFERENCES resource(path),type TEXT,source INTEGER,is_public INTEGER,create_time TEXT DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE web_info (id INTEGER PRIMARY KEY,web_name TEXT,web_title TEXT,notices TEXT,footer TEXT,background_image TEXT,avatar TEXT,random_avatar TEXT,random_name TEXT,random_cover TEXT,waifu_json TEXT,status INTEGER);
CREATE TABLE resource_path (id INTEGER PRIMARY KEY,title TEXT,classify TEXT,cover TEXT,url TEXT,introduction TEXT,type TEXT,status INTEGER,remark TEXT,create_time TEXT DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE resource (id INTEGER PRIMARY KEY,user_id INTEGER,type TEXT,path TEXT UNIQUE,size INTEGER,original_name TEXT,mime_type TEXT,status INTEGER,store_type TEXT,create_time TEXT DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE member_images (user_id INTEGER NOT NULL REFERENCES user(id) ON DELETE CASCADE,path TEXT NOT NULL REFERENCES resource(path) ON DELETE CASCADE,created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(user_id,path));
CREATE TABLE family (id INTEGER PRIMARY KEY,user_id INTEGER,bg_cover TEXT,man_cover TEXT,woman_cover TEXT,man_name TEXT,woman_name TEXT,timing TEXT,countdown_title TEXT,countdown_time TEXT,status INTEGER,family_info TEXT,like_count INTEGER,create_time TEXT DEFAULT CURRENT_TIMESTAMP,update_time TEXT DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE im_chat_user_friend (id INTEGER PRIMARY KEY,user_id INTEGER NOT NULL,friend_id INTEGER NOT NULL,friend_status INTEGER,remark TEXT,create_time TEXT DEFAULT CURRENT_TIMESTAMP,UNIQUE(user_id,friend_id));
CREATE TABLE im_chat_group (id INTEGER PRIMARY KEY,group_name TEXT,master_user_id INTEGER,avatar TEXT,introduction TEXT,notice TEXT,in_type INTEGER,group_type INTEGER,create_time TEXT DEFAULT CURRENT_TIMESTAMP);
CREATE TABLE im_chat_group_user (id INTEGER PRIMARY KEY,group_id INTEGER NOT NULL,user_id INTEGER NOT NULL,verify_user_id INTEGER,remark TEXT,admin_flag INTEGER,user_status INTEGER,create_time TEXT DEFAULT CURRENT_TIMESTAMP,UNIQUE(group_id,user_id));
CREATE TABLE im_chat_user_message (id INTEGER PRIMARY KEY,from_id INTEGER NOT NULL,to_id INTEGER NOT NULL,content TEXT NOT NULL,message_status INTEGER,create_time TEXT DEFAULT CURRENT_TIMESTAMP);
CREATE INDEX im_user_messages_to ON im_chat_user_message(to_id,create_time,id);
CREATE TABLE im_chat_user_group_message (id INTEGER PRIMARY KEY,group_id INTEGER NOT NULL,from_id INTEGER NOT NULL,to_id INTEGER,content TEXT NOT NULL,create_time TEXT DEFAULT CURRENT_TIMESTAMP);
CREATE INDEX im_group_messages_group ON im_chat_user_group_message(group_id,create_time,id);
