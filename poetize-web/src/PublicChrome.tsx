import {useEffect, useMemo, useState, type ReactNode} from 'react';
import {request, type Category, type SiteInfo} from './api';

const links = [
  ['🏡', '首页', '/'],
  ['💖', '家', '/love'],
  ['🌈', '游记', '/menory'],
  ['🏖️', '随笔', '/jotting'],
  ['📒', '记录', '/sort'],
  ['📸', '相册', '/travel'],
  ['🧰', '百宝箱', '/favorite'],
  ['📪', '留言', '/message'],
] as const;
const moreLinks = [
  ['🔎', '搜索', '/search'],
  ['💻', '后台管理', '/admin'],
  ['💬', '联系我', '/im'],
] as const;

function safeCover(value: string | null | undefined) {
  if (!value) return '/live/poetize-current-hero.jpg';
  try {
    const url = new URL(value, window.location.origin);
    return ['http:', 'https:'].includes(url.protocol) ? url.href : '/live/poetize-current-hero.jpg';
  } catch {
    return '/live/poetize-current-hero.jpg';
  }
}
function randomCover(value:string|null|undefined){if(!value)return null;let items:string[]=[];try{const parsed:unknown=JSON.parse(value);if(Array.isArray(parsed))items=parsed.filter((item):item is string=>typeof item==='string');}catch{items=value.split('\n').map(item=>item.trim()).filter(Boolean);}return items.length?items[Math.floor(Math.random()*items.length)]:null;}

export function PublicChrome({site, title, home = false, cover, articleMeta, plainHeader = false, subtitle, children}: {
  site?: SiteInfo | null;
  title?: string;
  home?: boolean;
  cover?: string | null;
  articleMeta?: {author: string | null; date: string | null; views: number; comments: number; likes: number};
  plainHeader?: boolean;
  subtitle?: string;
  children: ReactNode;
}) {
  const [loadedSite, setLoadedSite] = useState<SiteInfo | null>(null);
  const [scrolled, setScrolled] = useState(false);
  const [menuOpen, setMenuOpen] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [dark, setDark] = useState(() => { try { return window.localStorage.getItem('poetize-theme') === 'dark'; } catch { return false; } });
  const [quiet, setQuiet] = useState(() => { try { return window.localStorage.getItem('poetize-quiet') === 'true'; } catch { return false; } });
  const [categories,setCategories]=useState<Category[]>([]);
  const [member,setMember]=useState<{username:string;avatar:string|null;csrf_token:string}|null>(null);
  useEffect(() => {
    if (site !== undefined) return;
    const controller = new AbortController();
    void request<SiteInfo>('/api/v2/site', {signal: controller.signal}).then(setLoadedSite).catch(() => {});
    return () => controller.abort();
  }, [site]);
  useEffect(() => {
    const update = () => setScrolled(window.scrollY > 40);
    update();
    window.addEventListener('scroll', update, {passive: true});
    return () => window.removeEventListener('scroll', update);
  }, []);
  useEffect(()=>{void request<Category[]>('/api/v2/categories').then(setCategories).catch(()=>{});void request<{username:string;avatar:string|null;csrf_token:string}>('/api/v2/members/session').then(setMember).catch(()=>{});},[]);
  useEffect(() => {
    const close = (event: KeyboardEvent) => { if (event.key === 'Escape') { setMenuOpen(false); setSettingsOpen(false); document.querySelectorAll<HTMLDetailsElement>('.original-public .public-header details[open]').forEach(item => { item.open = false; }); } };
    window.addEventListener('keydown', close);
    return () => window.removeEventListener('keydown', close);
  }, []);
  function toggleDark() { setDark(value => { const next = !value; try { window.localStorage.setItem('poetize-theme', next ? 'dark' : 'light'); } catch { /* Storage may be unavailable. */ } return next; }); }
  function toggleQuiet() { setQuiet(value => { const next = !value; try { window.localStorage.setItem('poetize-quiet', String(next)); } catch { /* Storage may be unavailable. */ } return next; }); }
  async function logout(){if(!member)return;try{await request('/api/v2/members/logout',{method:'POST'},member.csrf_token);setMember(null);window.location.assign('/');}catch{/* The session may have expired already. */}}
  const info = site === undefined ? loadedSite : site;
  const fallbackCover=useMemo(()=>randomCover(info?.random_cover),[info?.random_cover]);
  return <div className={`public-site original-public page-${window.location.pathname.slice(1).replace(/[^a-zA-Z0-9_-]/g,'') || 'home'}${dark ? ' theme-dark' : ''}${quiet ? ' quiet-motion' : ''}`}>
    <header className={`public-header${scrolled || menuOpen || plainHeader ? ' entered' : ''}${plainHeader ? ' plain-header' : ''}`}>
      <a className="brand" href="/" aria-label={`${info?.web_name || 'POETIZE'} 首页`}><img src="/live/poetize-logo.png" alt={info?.web_name || 'POETIZE'}/></a>
      <button className="public-menu-toggle" type="button" aria-label={menuOpen ? '关闭导航' : '打开导航'} aria-expanded={menuOpen} onClick={() => setMenuOpen(value => !value)}>{menuOpen ? '✕' : '☰'}</button>
      <nav className={menuOpen ? 'open' : ''} aria-label="主导航"><span className="public-mobile-title">欢迎光临</span>{links.map(([icon, label, href]) => href==='/sort'?<details key={href} className="public-sort-menu"><summary><span aria-hidden="true">{icon}</span> {label}</summary><div><a href="/sort">全部文章</a>{categories.map(category=><a href={`/sort?sort_id=${category.id}`} key={category.id}>{category.sort_name}<small>{category.article_count}</small></a>)}</div></details>:href==='/favorite'?<details key={href} className="public-sort-menu"><summary><span aria-hidden="true">{icon}</span> {label}</summary><div><a href="/music">🎵 音乐</a><a href="/favorite?tab=favorites">📁 收藏夹</a><a href="/friend">🔗 友链</a></div></details>:href==='/'?<details key={href} className="public-sort-menu"><summary><span aria-hidden="true">{icon}</span> {label}</summary><div><a href="/">🏠 博客</a><a href="/search">🔍 起始页</a><a href="/sort">🗂️ 内容</a><a href="/sort">🏷️ 专栏</a></div></details>:<a key={href} href={href} aria-current={window.location.pathname === href ? 'page' : undefined}><span aria-hidden="true">{icon}</span> {label}</a>)}{member?<details className="public-avatar-menu"><summary aria-label="用户菜单"><img src={safeCover(member.avatar||info?.avatar || '/legacy/avatar.jpg')} alt=""/></summary><div><span className="public-menu-username">{member.username}</span><a href="/user">👤 个人中心</a><button type="button" onClick={()=>void logout()}>退出登录</button><span className="public-menu-divider"/>{moreLinks.map(([icon,label,href])=><a key={href} href={href}><span aria-hidden="true">{icon}</span> {label}</a>)}</div></details>:<a className="public-login-link" href="/im">登录</a>}</nav>
    </header>
    {!plainHeader && <section className={`hero${home ? ' home-hero' : articleMeta ? ' article-hero' : ' compact-hero'}`} style={{backgroundImage: `linear-gradient(#0b2c3e3d,#0b2c3e52),url("${safeCover(cover || info?.background_image || fallbackCover)}")`}}>
      <div className={articleMeta ? 'hero-article-info' : 'hero-center'}><h1>{home ? info?.web_title || '相信记录的力量' : title || 'POETIZE'}</h1>{home && <p className="hero-poem">相信记录的力量！<span className="cursor">|</span></p>}{subtitle && <p className="hero-subtitle">{subtitle}</p>}{articleMeta && <p className="hero-article-meta">👤 {articleMeta.author||'站长'}　·　📅 {articleMeta.date || '最近'}　·　🔥 {articleMeta.views} 热度　·　💬 {articleMeta.comments} 评论　·　❤️ {articleMeta.likes} 点赞</p>}</div>
      {home && <><a className="hero-down" href="#public-content" aria-label="浏览文章">⌄</a><div className="hero-wave hero-wave-back" aria-hidden="true"/><div className="hero-wave hero-wave-front" aria-hidden="true"/></>}
    </section>}
    <main id="public-content" className={`public-main${home ? '' : ' extra-main'}`}>{children}</main>
    <footer className="public-footer"><span>{info?.footer || 'POETIZE'}</span><small>本网站由 POETIZE 强力支持</small></footer>
    <div className="public-tools">{scrolled && <button className="back-top" type="button" onClick={() => window.scrollTo({top: 0, behavior: 'smooth'})} aria-label="返回顶部">🚀</button>}<div className="public-settings"><button className="settings-toggle" type="button" aria-label="显示设置" aria-expanded={settingsOpen} onClick={()=>setSettingsOpen(value=>!value)}>⚙</button>{settingsOpen&&<div className="settings-panel"><button type="button" onClick={toggleDark}>{dark?'☀ 浅色模式':'☾ 深色模式'}</button><button type="button" onClick={toggleQuiet}>{quiet?'✦ 开启动效':'✧ 减少动效'}</button></div>}</div></div>
  </div>;
}
