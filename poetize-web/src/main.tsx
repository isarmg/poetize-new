import {StrictMode, useEffect, useMemo, useRef, useState} from 'react';
import {createRoot} from 'react-dom/client';
import {createSarmgAdminApplication, useAdminApplication} from '@sarmg/admin-shell';
import {Button, EmptyState, ErrorState, FormField, LoadingState, PageHeader, Select, Table, TextField} from '@sarmg/admin-ui';
import '@sarmg/design-tokens/tokens.css';
import '@sarmg/design-tokens/tokens.dark.css';
import '@sarmg/admin-ui/styles.css';
import '@sarmg/design-tokens/reset.css';
import '@sarmg/design-tokens/accessibility.css';
import '@sarmg/web-fonts/fonts.css';
import './style.css';
import './public-original.css';
import {articleExcerpt, request, type Article, type ArticleInput, type ArticleSummary, type Category, type Page, type PublicLabel, type SiteInfo} from './api';
import {Community} from './Community';
import {CommentsPage, Dashboard, FamilyPage, LabelsPage, LinksPage, NotesPage, ResourcesPage, SitePage, TreeHolePage, UsersPage} from './AdminExtras';
import {PublicExtras} from './PublicExtras';
import {HomeSectionsPage,type HomeSection} from './HomeSections';
import {PublicChrome} from './PublicChrome';
import {PublicComments} from './PublicComments';
import {ArticleNews} from './ArticleNews';
import {ImageLightbox} from './MediaPreview';
// Browser bundles are vendored from the original POETIZE dependencies, with their licenses.
// @ts-expect-error The original browser bundle does not publish TypeScript declarations.
import MarkdownIt from './vendor/markdown-it.mjs';
// @ts-expect-error The original browser bundle does not publish TypeScript declarations.
import DOMPurify from './vendor/dompurify.mjs';

const markdown = new MarkdownIt({html: false, linkify: true, breaks: true});
function safeMediaUrl(value:string|null){if(!value)return null;try{const url=new URL(value,window.location.origin);return ['http:','https:'].includes(url.protocol)?url.href:null;}catch{return null;}}

function ReadingArticle({article}: {article: Article}) {
  const readingRef=useRef<HTMLElement|null>(null);
  const [progress,setProgress]=useState(0);
  const [active,setActive]=useState('');
  const [copyrightOpen,setCopyrightOpen]=useState(false);
  const [lightbox,setLightbox]=useState<string|null>(null);
  const [copyStatus,setCopyStatus]=useState('');
  const rendered=useMemo(()=>{const holder=document.createElement('div');holder.innerHTML=DOMPurify.sanitize(markdown.render(article.article_content));const headings=Array.from(holder.querySelectorAll('h2,h3,h4')).map((node,index)=>{const id=`section-${index+1}`;node.id=id;return {id,title:node.textContent?.trim()||`第 ${index+1} 节`,level:Number(node.tagName[1])};});holder.querySelectorAll('pre').forEach(pre=>{const button=document.createElement('button');button.type='button';button.className='article-copy-code';button.textContent='复制代码';pre.prepend(button);});holder.querySelectorAll('img').forEach(img=>{img.setAttribute('role','button');img.setAttribute('tabindex','0');img.setAttribute('aria-label',`查看图片：${img.alt||'文章插图'}`);});return {html:holder.innerHTML,headings};},[article.article_content]);
  async function articleAction(target:EventTarget|null){if(!(target instanceof Element))return;const button=target.closest('.article-copy-code');if(button){const code=button.parentElement?.querySelector('code')?.textContent||'';try{await navigator.clipboard.writeText(code);setCopyStatus('代码已复制');window.setTimeout(()=>setCopyStatus(''),2000);}catch{setCopyStatus('复制失败，请手动选中代码');}return;}const img=target.closest('img');if(img&&img.closest('.article-markdown'))setLightbox(safeMediaUrl(img.getAttribute('src')));}
  useEffect(()=>{let frame=0;function update(){cancelAnimationFrame(frame);frame=requestAnimationFrame(()=>{const card=readingRef.current;if(!card)return;const start=card.getBoundingClientRect().top+window.scrollY;const end=Math.max(start+1,start+card.offsetHeight-window.innerHeight);setProgress(Math.round(Math.min(100,Math.max(0,(window.scrollY-start)/(end-start)*100))));let current='';for(const heading of rendered.headings){const target=document.getElementById(heading.id);if(target&&target.getBoundingClientRect().top<=150)current=heading.id;}setActive(current);});}update();window.addEventListener('scroll',update,{passive:true});window.addEventListener('resize',update);return()=>{cancelAnimationFrame(frame);window.removeEventListener('scroll',update);window.removeEventListener('resize',update);};},[rendered]);
  const outline=<ol>{rendered.headings.map(heading=><li key={heading.id} className={`toc-level-${heading.level}`}><a className={active===heading.id?'active':''} href={`#${heading.id}`}>{heading.title}</a></li>)}</ol>;
  return <><div className="reading-progress" role="progressbar" aria-label="阅读进度" aria-valuemin={0} aria-valuemax={100} aria-valuenow={progress}><span style={{width:`${progress}%`}}/></div><div className="reading-layout"><article className="reading-card reading-article" ref={readingRef}><a href="/">← 返回文章</a>{safeMediaUrl(article.video_url)&&<video className="article-video" controls preload="metadata" poster={safeMediaUrl(article.article_cover)||undefined} src={safeMediaUrl(article.video_url)||undefined}/>}{article.password_required===0&&<ArticleNews articleId={article.id} authorId={article.user_id}/>}<div className="article-markdown" onClick={event=>void articleAction(event.target)} onKeyDown={event=>{if((event.key==='Enter'||event.key===' ')&&event.target instanceof HTMLImageElement){event.preventDefault();void articleAction(event.target);}}} dangerouslySetInnerHTML={{__html:rendered.html}} />{copyStatus&&<span className="article-copy-status" role="status">{copyStatus}</span>}<div className="article-end-info"><p>文章最后更新于 {article.update_time||article.create_time||'最近'}</p><a href={`/sort?sort_id=${article.sort_id}&label_id=${article.label_id}`}>{article.sort_name||'分类'} ▶ {article.label_name||'标签'}</a><blockquote>作者：{article.username||'站长'}<br/>版权与许可请详阅 <button type="button" onClick={()=>setCopyrightOpen(true)}>版权声明</button></blockquote></div>{article.comment_status===1&&article.password_required===0&&<PublicComments articleId={article.id}/>}</article>{rendered.headings.length>0&&<aside className="reading-toc" aria-label="文章目录"><nav><h3>目录</h3>{outline}</nav><details><summary>文章目录 · {progress}%</summary>{outline}</details></aside>}</div>{copyrightOpen&&<div className="article-copyright-backdrop" role="presentation" onClick={()=>setCopyrightOpen(false)}><section role="dialog" aria-modal="true" aria-label="版权声明" onClick={event=>event.stopPropagation()}><button type="button" aria-label="关闭版权声明" onClick={()=>setCopyrightOpen(false)}>×</button><h2>版权声明</h2><p>文章、图片和视频的著作权归各自原作者所有。转载或使用内容时，请遵守原作者的许可要求；本站原创内容采用署名－非商业性使用－相同方式共享 4.0 国际许可协议。</p><p><a href="https://creativecommons.org/licenses/by-nc-sa/4.0/" target="_blank" rel="noopener noreferrer">查看许可协议</a></p></section></div>}<ImageLightbox src={lightbox} onClose={()=>setLightbox(null)}/></>;
}

function hasItems(value: unknown): value is Page<ArticleSummary> {
  return typeof value === 'object' && value !== null && 'items' in value && Array.isArray(value.items) && 'total' in value && typeof value.total === 'number';
}
function isArticle(value: unknown): value is Article {
  return typeof value === 'object' && value !== null && 'id' in value && typeof value.id === 'number' && 'article_title' in value && typeof value.article_title === 'string' && 'article_content' in value && typeof value.article_content === 'string';
}
function isCategories(value: unknown): value is Category[] {
  return Array.isArray(value) && value.every(item => typeof item === 'object' && item !== null && typeof item.id === 'number' && typeof item.sort_name === 'string');
}
function isDelete(value: unknown): value is {deleted: boolean} {
  return typeof value === 'object' && value !== null && 'deleted' in value && value.deleted === true;
}

function AdminPages() {
  const [page, setPage] = useState(() => window.location.hash.slice(1) || 'articles');
  useEffect(() => { const update = () => setPage(window.location.hash.slice(1) || 'articles'); window.addEventListener('hashchange', update); return () => window.removeEventListener('hashchange', update); }, []);
  if (page === 'dashboard') return <Dashboard />;
  if (page === 'users') return <UsersPage />;
  if (page === 'comments') return <CommentsPage />;
  if (page === 'notes') return <NotesPage />;
  if (page === 'site') return <SitePage />;
  if (page === 'home') return <HomeSectionsPage />;
  if (page === 'labels') return <LabelsPage />;
  if (page === 'resources') return <ResourcesPage />;
  if (page === 'links') return <LinksPage />;
  if (page === 'tree-hole') return <TreeHolePage />;
  if (page === 'family') return <FamilyPage />;
  if (page === 'categories') return <CategoryManager />;
  if (page.startsWith('edit/')) return <ArticleEditor key={page} id={Number(page.slice(5))} />;
  if (page === 'new') return <ArticleEditor key="new" />;
  return <ArticleManager />;
}

const AdminApp = createSarmgAdminApplication({
  product: {name: 'POETIZE'},
  navigation: [
    {label: '总览', href: '/admin#dashboard'},
    {label: '文章管理', href: '/admin#articles'},
    {label: '首页栏目', href: '/admin#home'},
    {label: '分类管理', href: '/admin#categories'},
    {label: '标签管理', href: '/admin#labels'},
    {label: '评论管理', href: '/admin#comments'},
    {label: '动态管理', href: '/admin#notes'},
    {label: '留言板管理', href: '/admin#tree-hole'},
    {label: '用户管理', href: '/admin#users'},
    {label: '文件管理', href: '/admin#resources'},
    {label: '内容资源', href: '/admin#links'},
    {label: '恋爱笔记', href: '/admin#family'},
    {label: '网站设置', href: '/admin#site'},
    {label: '返回网站', href: '/'},
  ],
  loginLandingHref: '/admin#articles',
  routes: <AdminPages />,
});

function ArticleManager() {
  const {client, notify} = useAdminApplication();
  const [result, setResult] = useState<Page<ArticleSummary> | null>(null);
  const [failure, setFailure] = useState('');
  const [busy, setBusy] = useState(false);
  const [refresh, setRefresh] = useState(0);
  const [page, setPage] = useState(1);
  useEffect(() => {
    const controller = new AbortController();
    setBusy(true);
    setFailure('');
    void client.request(`/api/v2/content/articles?page=${page}&size=15`, hasItems, {signal: controller.signal})
      .then(data => { if (!controller.signal.aborted) setResult(data); })
      .catch(() => { if (!controller.signal.aborted) setFailure('文章加载失败'); })
      .finally(() => { if (!controller.signal.aborted) setBusy(false); });
    return () => controller.abort();
  }, [client, refresh, page]);
  async function remove(id: number) {
    if (!window.confirm('确定删除这篇文章？')) return;
    try {
      await client.request(`/api/v2/content/articles/${id}`, isDelete, {method: 'DELETE'});
      notify('文章已删除'); setRefresh(value => value+1);
    } catch { setFailure('删除失败，请刷新后重试'); }
  }
  return <section className="sarmg-content-stack">
    <PageHeader><div><p className="eyebrow">CONTENT</p><h1>文章管理</h1><p>管理公开文章与草稿。</p></div><div className="sarmg-actions"><Button onClick={() => setRefresh(value => value+1)}>刷新</Button><Button className="primary-action" onClick={() => {window.location.hash='new';}}>新增文章</Button></div></PageHeader>
    {failure && <ErrorState onRetry={() => setRefresh(value => value+1)}>{failure}</ErrorState>}
    {busy && !result ? <LoadingState /> : result?.items.length ? <Table aria-label="文章列表"><thead><tr><th>标题</th><th>状态</th><th>浏览</th><th>创建时间</th><th>操作</th></tr></thead><tbody>{result.items.map(item => <tr key={item.id}><td><strong>{item.article_title}</strong><small className="muted">#{item.id}</small></td><td><span className={item.view_status ? 'badge success' : 'badge'}>{item.view_status ? '公开' : '加密'}</span></td><td>{item.view_count}</td><td>{item.create_time || '—'}</td><td><div className="sarmg-actions"><Button onClick={() => {window.location.hash=`edit/${item.id}`;}}>编辑</Button><Button className="sarmg-danger" onClick={() => void remove(item.id)}>删除</Button></div></td></tr>)}</tbody></Table> : <EmptyState>还没有文章。点击“新增文章”开始创作。</EmptyState>}
    <div className="pager"><Button disabled={page<=1} onClick={() => setPage(value => value-1)}>上一页</Button><span>第 {page} 页 · 共 {result?.total ?? 0} 篇</span><Button disabled={!result || page*15>=result.total} onClick={() => setPage(value => value+1)}>下一页</Button></div>
  </section>;
}

const emptyArticle: ArticleInput = {article_title:'',article_content:'',article_cover:null,video_url:null,sort_id:0,label_id:0,view_status:true,recommend_status:false,comment_status:true,password:null,tips:null};
function ArticleEditor({id}: {id?: number}) {
  const {client, notify} = useAdminApplication();
  const [article, setArticle] = useState<ArticleInput>(emptyArticle);
  const [categories, setCategories] = useState<Category[]>([]);
  const [labels, setLabels] = useState<{id:number;sort_id:number;label_name:string}[]>([]);
  const [saving, setSaving] = useState(false);
  const [preview,setPreview]=useState(false);
  const [uploading,setUploading]=useState(false);
  const [failure, setFailure] = useState('');
  const previewHtml=useMemo(()=>DOMPurify.sanitize(markdown.render(article.article_content)),[article.article_content]);
  useEffect(() => {
    const controller = new AbortController();
    void request<Category[]>('/api/v2/categories', {signal: controller.signal}).then(value => {if (!controller.signal.aborted) setCategories(value);}).catch(() => setFailure('分类加载失败'));
    void client.request('/api/v2/content/labels', (value): value is {id:number;sort_id:number;label_name:string}[] => Array.isArray(value), {signal:controller.signal}).then(value => {if (!controller.signal.aborted) setLabels(value);}).catch(() => setFailure('标签加载失败'));
    if (id) void client.request(`/api/v2/content/articles/${id}`, isArticle, {signal: controller.signal}).then(value => {
      if (!controller.signal.aborted) setArticle({article_title:value.article_title,article_content:value.article_content,article_cover:value.article_cover,video_url:value.video_url,sort_id:value.sort_id,label_id:value.label_id,view_status:Boolean(value.view_status),recommend_status:Boolean(value.recommend_status),comment_status:Boolean(value.comment_status),password:null,tips:value.tips});
    }).catch(() => setFailure('文章加载失败'));
    return () => controller.abort();
  }, [client,id]);
  function change<K extends keyof ArticleInput>(key: K, value: ArticleInput[K]) {setArticle(current => ({...current,[key]:value}));}
  async function uploadPicture(file:File|undefined,target:'cover'|'content'){
    if(!file)return;
    if(file.size>10*1024*1024||!['image/png','image/jpeg','image/gif','image/webp'].includes(file.type)){setFailure('请选择不超过 10 MB 的 PNG、JPEG、GIF 或 WebP 图片');return;}
    setUploading(true);setFailure('');
    try{const result=await client.request('/api/v2/content/upload',(value):value is {path:string}=>typeof value==='object'&&value!==null&&'path'in value&&typeof value.path==='string',{method:'POST',headers:{'Content-Type':file.type,'X-File-Name':file.name.replace(/[^\x20-\x7e]/g,'_').slice(0,200)},body:file});if(target==='cover')change('article_cover',result.path);else setArticle(current=>({...current,article_content:`${current.article_content}\n\n![${file.name.replace(/[\[\]]/g,'')}](${result.path})\n`}));notify('图片已上传');}catch(reason){setFailure(reason instanceof Error?reason.message:'图片上传失败');}finally{setUploading(false);}
  }
  async function save(event: React.FormEvent) {
    event.preventDefault(); setSaving(true); setFailure('');
    try {
      await client.request(id ? `/api/v2/content/articles/${id}` : '/api/v2/content/articles', isArticle, {method:id?'PUT':'POST',body:JSON.stringify(article)});
      notify('文章已保存'); window.location.hash='articles';
    } catch { setFailure('文章保存失败，请检查内容后重试'); }
    finally {setSaving(false);}
  }
  return <section className="sarmg-content-stack"><PageHeader><div><p className="eyebrow">EDITOR</p><h1>{id ? '编辑文章' : '新增文章'}</h1><p>支持 Markdown 正文，发布后可从网站访问。</p></div></PageHeader>
    {failure && <ErrorState>{failure}</ErrorState>}
    <form className="editor-form" onSubmit={event => void save(event)}>
      <FormField label="标题"><TextField required maxLength={120} value={article.article_title} onChange={event => change('article_title',event.target.value)} /></FormField>
      <div className="form-grid"><FormField label="分类"><Select required value={article.sort_id} onChange={event => {change('sort_id',Number(event.target.value));change('label_id',0);}}><option value={0}>选择分类</option>{categories.map(item => <option key={item.id} value={item.id}>{item.sort_name}</option>)}</Select></FormField><FormField label="标签"><Select required value={article.label_id} onChange={event => change('label_id',Number(event.target.value))}><option value={0}>选择标签</option>{labels.filter(item=>item.sort_id===article.sort_id).map(item=><option key={item.id} value={item.id}>{item.label_name}</option>)}</Select></FormField></div>
      <FormField label="封面图片地址"><TextField value={article.article_cover || ''} onChange={event => change('article_cover',event.target.value || null)} /><input type="file" accept="image/png,image/jpeg,image/gif,image/webp" disabled={uploading} aria-label="上传封面图片" onChange={event=>void uploadPicture(event.target.files?.[0],'cover')}/></FormField>
      <FormField label="Markdown 正文"><textarea className="sarmg-input editor-textarea" required value={article.article_content} onChange={event => change('article_content',event.target.value)} /><div className="sarmg-actions"><Button type="button" onClick={()=>setPreview(value=>!value)}>{preview?'收起预览':'预览正文'}</Button><label>插入图片 <input type="file" accept="image/png,image/jpeg,image/gif,image/webp" disabled={uploading} aria-label="插入正文图片" onChange={event=>void uploadPicture(event.target.files?.[0],'content')}/></label></div>{preview&&<div className="editor-markdown-preview article-markdown" dangerouslySetInnerHTML={{__html:previewHtml}}/>}</FormField>
      <div className="form-grid"><FormField label="视频地址"><TextField value={article.video_url || ''} onChange={event => change('video_url',event.target.value || null)} /></FormField>{!article.view_status&&<FormField label="文章访问密码"><TextField type="password" minLength={12} placeholder={id?'留空则保留原密码':'至少 12 字节'} value={article.password || ''} onChange={event => change('password',event.target.value || null)} /></FormField>}</div>
      <div className="check-row"><label><input type="checkbox" checked={article.view_status} onChange={event => {change('view_status',event.target.checked);if(event.target.checked)change('password',null);}} /> 公开访问（关闭后需设置密码）</label><label><input type="checkbox" checked={article.recommend_status} onChange={event => change('recommend_status',event.target.checked)} /> 推荐</label><label><input type="checkbox" checked={article.comment_status} onChange={event => change('comment_status',event.target.checked)} /> 允许评论</label></div>
      <div className="sarmg-actions"><Button onClick={() => {window.location.hash='articles';}}>取消</Button><Button type="submit" className="primary-action" disabled={saving}>{saving?'保存中…':'保存文章'}</Button></div>
    </form>
    {id&&<ArticleNewsEditor id={id}/>}
  </section>;
}

type NewsEntry={id:number;content:string;create_time:string|null};
function ArticleNewsEditor({id}:{id:number}){
  const {client,notify}=useAdminApplication();
  const [entries,setEntries]=useState<NewsEntry[]>([]);const [content,setContent]=useState('');const [date,setDate]=useState('');const [failure,setFailure]=useState('');
  const isNews=(value:unknown):value is NewsEntry[]=>Array.isArray(value)&&value.every(item=>typeof item==='object'&&item!==null&&'id'in item&&typeof item.id==='number');
  useEffect(()=>{const controller=new AbortController();void client.request(`/api/v2/content/articles/${id}/news`,isNews,{signal:controller.signal}).then(setEntries).catch(()=>{if(!controller.signal.aborted)setFailure('文章进展加载失败');});return()=>controller.abort();},[client,id]);
  async function publish(event:React.FormEvent){event.preventDefault();try{const saved=await client.request(`/api/v2/content/articles/${id}/news`,(value):value is NewsEntry=>typeof value==='object'&&value!==null&&'id'in value&&typeof value.id==='number',{method:'POST',body:JSON.stringify({content,create_time:date||null})});setEntries(current=>[saved,...current].sort((a,b)=>(b.create_time||'').localeCompare(a.create_time||'')));setContent('');setDate('');setFailure('');notify('文章进展已发布');}catch(reason){setFailure(reason instanceof Error?reason.message:'发布失败');}}
  async function remove(entryId:number){if(!window.confirm('确定删除这条文章进展？'))return;try{await client.request(`/api/v2/content/articles/${id}/news/${entryId}`,isDelete,{method:'DELETE'});setEntries(current=>current.filter(item=>item.id!==entryId));notify('文章进展已删除');}catch(reason){setFailure(reason instanceof Error?reason.message:'删除失败');}}
  return <section className="editor-form"><h2>最新进展</h2>{failure&&<ErrorState>{failure}</ErrorState>}<form className="editor-form" onSubmit={event=>void publish(event)}><FormField label="进展内容"><textarea className="sarmg-input" required maxLength={1024} value={content} onChange={event=>setContent(event.target.value)}/></FormField><FormField label="进展时间"><TextField type="datetime-local" value={date} onChange={event=>setDate(event.target.value)}/></FormField><Button type="submit" className="primary-action">发布进展</Button></form>{entries.length?<Table aria-label="文章进展"><thead><tr><th>时间</th><th>内容</th><th>操作</th></tr></thead><tbody>{entries.map(item=><tr key={item.id}><td>{item.create_time||'—'}</td><td className="comment-cell">{item.content}</td><td><Button className="sarmg-danger" onClick={()=>void remove(item.id)}>删除</Button></td></tr>)}</tbody></Table>:<EmptyState>暂无进展</EmptyState>}</section>;
}

function CategoryManager() {
  const {client, notify} = useAdminApplication();
  const [categories, setCategories] = useState<Category[]>([]);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [priority, setPriority] = useState(0);
  const [editing, setEditing] = useState<number | null>(null);
  const [failure, setFailure] = useState('');
  const [refresh, setRefresh] = useState(0);
  useEffect(() => {const controller = new AbortController(); void client.request('/api/v2/categories',isCategories,{signal:controller.signal}).then(value => {if (!controller.signal.aborted) setCategories(value);}).catch(() => setFailure('分类加载失败')); return () => controller.abort();},[client,refresh]);
  function edit(item:Category){setEditing(item.id);setName(item.sort_name);setDescription(item.sort_description||'');setPriority(item.priority??0);}
  function reset(){setEditing(null);setName('');setDescription('');setPriority(0);}
  async function save(event: React.FormEvent) {event.preventDefault(); try {await client.request(editing?`/api/v2/content/categories/${editing}`:'/api/v2/content/categories', (value): value is Category => typeof value==='object'&&value!==null&&'id' in value&&typeof value.id==='number', {method:editing?'PUT':'POST',body:JSON.stringify({name,description:description||null,priority})});reset();setRefresh(value=>value+1);notify('分类已保存');setFailure('');} catch(reason) {setFailure(reason instanceof Error?reason.message:'分类保存失败');}}
  async function remove(id:number){if(!window.confirm('确定删除这个分类？'))return;try{await client.request(`/api/v2/content/categories/${id}`,isDelete,{method:'DELETE'});setRefresh(value=>value+1);notify('分类已删除');}catch(reason){setFailure(reason instanceof Error?reason.message:'分类删除失败');}}
  return <section className="sarmg-content-stack"><PageHeader><div><p className="eyebrow">STRUCTURE</p><h1>分类管理</h1></div></PageHeader>{failure&&<ErrorState>{failure}</ErrorState>}<form className="inline-form" onSubmit={event=>void save(event)}><FormField label="分类名称"><TextField required maxLength={64} value={name} onChange={event=>setName(event.target.value)} /></FormField><FormField label="分类描述"><TextField maxLength={200} value={description} onChange={event=>setDescription(event.target.value)}/></FormField><FormField label="优先级"><TextField type="number" value={priority} onChange={event=>setPriority(Number(event.target.value)||0)}/></FormField><Button type="submit" className="primary-action">{editing?'保存修改':'创建分类'}</Button>{editing&&<Button type="button" onClick={reset}>取消编辑</Button>}</form>{categories.length?<Table aria-label="分类列表"><thead><tr><th>名称</th><th>描述</th><th>排序</th><th>操作</th></tr></thead><tbody>{categories.map(item=><tr key={item.id}><td>{item.sort_name}</td><td>{item.sort_description||'—'}</td><td>{item.priority??0}</td><td><div className="sarmg-actions"><Button onClick={()=>edit(item)}>编辑</Button><Button className="sarmg-danger" onClick={()=>void remove(item.id)}>删除</Button></div></td></tr>)}</tbody></Table>:<EmptyState>暂无分类</EmptyState>}</section>;
}

function HomeArticleCard({item}:{item:ArticleSummary}){
  const excerpt=articleExcerpt(item.excerpt,75);
  return <a className="article-card" href={`/article/${item.id}`}>
    <div className="article-card-cover">{safeMediaUrl(item.article_cover)?<img src={safeMediaUrl(item.article_cover)||''} alt="" loading="lazy"/>:<span>遇事不决，可问春风</span>}</div>
    <div className="article-card-body"><small>📅 发布于 {item.create_time||'最近'}</small><h3>{item.article_title}</h3><p className="article-card-stats">🔥 {item.view_count} 热度　💬 {item.comment_count} 评论　🧡 {item.like_count} 点赞</p>{excerpt&&<p className="article-card-excerpt">{excerpt}</p>}<span className="article-card-tags">{item.sort_name&&<span>📁 {item.sort_name}</span>}{item.label_name&&<span>🏷 {item.label_name}</span>}{item.view_status===0&&<span>🔒 密码文章</span>}</span></div>
  </a>;
}

function noticeList(value:string|null|undefined):string[]{if(!value)return [];try{const parsed:unknown=JSON.parse(value);if(Array.isArray(parsed))return parsed.filter((item):item is string=>typeof item==='string');}catch{/* Plain text notice. */}return value.split('\n').map(item=>item.trim()).filter(Boolean);}
function noticeText(value:string|null|undefined){return noticeList(value).filter(item=>!['推送标题：','推送封面：','推送链接：'].some(prefix=>item.startsWith(prefix))).join(' · ')||'欢迎光临';}
function pushNotice(value:string|null|undefined){const lines=noticeList(value);const title=lines.find(item=>item.startsWith('推送标题：'))?.slice(5);const cover=lines.find(item=>item.startsWith('推送封面：'))?.slice(5);const link=lines.find(item=>item.startsWith('推送链接：'))?.slice(5);return title&&link?{title,cover,link}:null;}

function HomeAside({site,categories,recommendedArticles,labels,messages,articleTotal,viewTotal}:{site:SiteInfo|null;categories:Category[];recommendedArticles:ArticleSummary[];labels:PublicLabel[];messages:{message:string}[];articleTotal:number;viewTotal:number}){
  return <aside className="home-aside" aria-label="网站侧栏">
    <section className="aside-profile"><div className="aside-avatar"><img src={safeMediaUrl(site?.avatar||null)||'/legacy/avatar.jpg'} alt="站长头像"/></div><h2>{site?.web_name||'POETIZE'}</h2><div className="aside-stats"><span>📖 文章<strong>{articleTotal}</strong></span><span>📒 分类<strong>{categories.length}</strong></span><span>🔥 访问量<strong>{viewTotal}</strong></span></div></section>
    <form className="aside-search" action="/search" method="get"><label htmlFor="home-search">搜索</label><div><input id="home-search" name="q" maxLength={200} placeholder="搜索文章"/><button type="submit" aria-label="搜索">⌕</button></div></form>
    {recommendedArticles.length>0&&<section className="aside-featured"><h2>✧ 推荐位</h2>{recommendedArticles.slice(0,2).map(item=><a key={item.id} href={`/article/${item.id}`}>{safeMediaUrl(item.article_cover)&&<img src={safeMediaUrl(item.article_cover)||''} alt="" loading="lazy"/>}<strong>{item.article_title}</strong></a>)}</section>}
    {recommendedArticles.length>0&&<section className="aside-recommended"><h2>🔥 推荐文章</h2>{recommendedArticles.map(item=><a href={`/article/${item.id}`} key={item.id}>{safeMediaUrl(item.article_cover)?<img src={safeMediaUrl(item.article_cover)||''} alt="" loading="lazy"/>:<span className="aside-recommend-cover">诗意生活</span>}<span>{item.article_title}<small>◷ {item.create_time||'最近'}</small></span></a>)}</section>}
    {labels.length>0&&<section className="aside-labels"><h2>🏷 标签</h2><div>{labels.slice(0,28).map(item=><a key={item.id} href={`/sort?sort_id=${item.sort_id}&label_id=${item.id}`}>{item.label_name}</a>)}</div></section>}
    {messages.length>0&&<section className="aside-latest"><h2>💬 最新弹幕</h2><div>{messages.slice(0,9).map((item,index)=><p key={index}>{item.message}</p>)}</div></section>}
    {categories.length>0&&<section className="aside-categories" aria-label="分类速览">{categories.map((item,index)=><a key={item.id} href={`/sort?sort_id=${item.id}`} style={{background:['linear-gradient(110deg,#358bff,#15c6ff)','linear-gradient(110deg,#18c9a7,#1eebeb)','linear-gradient(110deg,#ff6655,#ffbf37)','linear-gradient(110deg,#8c72e9,#d3a4f4)'][index%4]}}><small>速览</small><strong>{item.sort_name}</strong><span>{item.sort_description||'查看更多文章 →'}</span></a>)}</section>}
  </aside>;
}

function Site() {
  const [site,setSite] = useState<SiteInfo|null>(null);
  const [stats,setStats] = useState<{article_count:number;view_count:number}|null>(null);
  const [sections,setSections] = useState<HomeSection[]>([]);
  const [sectionItems,setSectionItems] = useState<Record<number,Page<ArticleSummary>>>({});
  const [article,setArticle] = useState<Article|null>(null);
  const [categories,setCategories] = useState<Category[]>([]);
  const [recommendedArticles,setRecommendedArticles] = useState<ArticleSummary[]>([]);
  const [labels,setLabels] = useState<PublicLabel[]>([]);
  const [messages,setMessages] = useState<{message:string}[]>([]);
  const [failure,setFailure] = useState('');
  const [needsPassword,setNeedsPassword] = useState(false);
  const [accessTips,setAccessTips] = useState<string|null>(null);
  const [password,setPassword] = useState('');
  const [pushOpen,setPushOpen]=useState(false);
  const articleId = /^\/article\/(\d+)$/.exec(window.location.pathname)?.[1];
  const push=useMemo(()=>pushNotice(site?.notices),[site?.notices]);
  useEffect(()=>{if(articleId||!push||!safeMediaUrl(push.link))return;const key=`poetize-push:${push.link}`;try{if(window.localStorage.getItem(key)==='seen')return;}catch{/* Storage may be disabled. */}const timer=window.setTimeout(()=>{setPushOpen(true);try{window.localStorage.setItem(key,'seen');}catch{/* Storage may be disabled. */}},2000);return()=>window.clearTimeout(timer);},[articleId,push]);
  useEffect(() => {
    const controller = new AbortController();
    void request<SiteInfo>('/api/v2/site',{signal:controller.signal}).then(setSite).catch(()=>setFailure('网站信息加载失败'));
    void request<{article_count:number;view_count:number}>('/api/v2/site/stats',{signal:controller.signal}).then(setStats).catch(()=>{});
    void request<Category[]>('/api/v2/categories',{signal:controller.signal}).then(setCategories).catch(()=>{});
    if (!articleId) void request<Page<ArticleSummary>>('/api/v2/articles?size=5&recommended=true',{signal:controller.signal}).then(result=>setRecommendedArticles(result.items)).catch(()=>{});
    if (!articleId) {void request<PublicLabel[]>('/api/v2/labels',{signal:controller.signal}).then(setLabels).catch(()=>{});void request<{message:string}[]>('/api/v2/tree-hole',{signal:controller.signal}).then(setMessages).catch(()=>{});}
    if (articleId) void request<Article>(`/api/v2/articles/${articleId}`,{signal:controller.signal}).then(setArticle).catch(()=>{if(controller.signal.aborted)return;void request<{password_required:number;tips:string|null}>(`/api/v2/articles/${articleId}/access`,{signal:controller.signal}).then(access=>{if(access.password_required){setNeedsPassword(true);setAccessTips(access.tips);}else setFailure('文章加载失败');}).catch(()=>{if(!controller.signal.aborted)setFailure('文章不存在');});});
    else void request<HomeSection[]>('/api/v2/home-sections',{signal:controller.signal}).then(setSections).catch(()=>setFailure('首页栏目加载失败'));
    return () => controller.abort();
  },[articleId]);
  useEffect(()=>{if(articleId||!sections.length)return;const controller=new AbortController();void Promise.all(sections.map(async section=>{const filter=section.kind==='recommended'?'&recommended=true':section.kind==='category'?`&sort_id=${section.sort_id}`:'';const result=await request<Page<ArticleSummary>>(`/api/v2/articles?size=6${filter}`,{signal:controller.signal});return [section.id,result] as const;})).then(results=>{if(!controller.signal.aborted)setSectionItems(Object.fromEntries(results));}).catch(()=>{if(!controller.signal.aborted)setFailure('首页文章加载失败');});return()=>controller.abort();},[articleId,sections]);
  async function unlock(event: React.FormEvent) {
    event.preventDefault();
    try {const found = await request<Article>(`/api/v2/articles/${articleId}/unlock`,{method:'POST',body:JSON.stringify({password})});setArticle(found);setNeedsPassword(false);setFailure('');setPassword('');}
    catch {setFailure('访问密码错误或文章不可用');}
  }
  const latest=sections.find(section=>section.kind==='latest');
  return <PublicChrome site={site} home={!articleId} title={article?.article_title||'文章'} cover={article?.article_cover} articleMeta={article?{author:article.username,date:article.create_time,views:article.view_count,comments:article.comment_count,likes:article.like_count}:undefined}>
    {failure&&<p role="alert">{failure}</p>}
    {articleId?article?<ReadingArticle article={article}/>:needsPassword?<form className="reading-card password-form" onSubmit={event=>void unlock(event)}><h2>这篇文章需要访问密码</h2>{accessTips&&<p>{accessTips}</p>}<label>访问密码<input type="password" required value={password} onChange={event=>setPassword(event.target.value)} /></label><button type="submit">阅读文章</button></form>:failure?null:<p>正在加载文章…</p>:
    <div className="home-layout"><HomeAside site={site} categories={categories} recommendedArticles={recommendedArticles} labels={labels} messages={messages} articleTotal={stats?.article_count??(latest?sectionItems[latest.id]?.total||0:0)} viewTotal={stats?.view_count||0}/><div className="home-content"><div className="announcement"><span aria-hidden="true">🔊</span><p>{noticeText(site?.notices)}</p></div>{sections.map(section=>{const page=sectionItems[section.id];const more=section.kind==='category'?`/sort?sort_id=${section.sort_id}`:section.kind==='recommended'?'/sort?recommended=1':'/sort';return <section className="home-section" key={section.id} aria-label={section.title}><div className="section-heading"><h2><span className="section-squares" aria-hidden="true"/>{section.title}</h2><a href={more}><span className="more-chevrons" aria-hidden="true">❯❯</span> MORE</a></div><div className="article-grid">{page?.items.map(item=><HomeArticleCard item={item} key={item.id}/>)}</div>{page&&page.items.length===0&&<p className="muted">这个栏目还没有文章。</p>}</section>;})}</div></div>}{pushOpen&&push&&safeMediaUrl(push.link)&&<div className="push-backdrop" role="presentation" onClick={()=>setPushOpen(false)}><section className="push-dialog" role="dialog" aria-modal="true" aria-label="每日推荐" onClick={event=>event.stopPropagation()}><button className="push-close" type="button" aria-label="关闭每日推荐" onClick={()=>setPushOpen(false)}>×</button><h2>每日推荐</h2><h3>{push.title}</h3>{safeMediaUrl(push.cover||null)&&<img src={safeMediaUrl(push.cover||null)||''} alt="推荐封面"/>}<a href={safeMediaUrl(push.link)||undefined} target="_blank" rel="noopener noreferrer" onClick={()=>setPushOpen(false)}>立即前往 →</a></section></div>}
  </PublicChrome>;
}

createRoot(document.getElementById('root')!).render(<StrictMode>{window.location.pathname.startsWith('/admin')?<AdminApp />:window.location.pathname.startsWith('/im')?<Community />:['/weiYan','/jotting','/menory','/message','/favorite','/friend','/music','/travel','/love','/sort','/search','/about','/letter','/user'].includes(window.location.pathname)?<PublicExtras/>:<Site />}</StrictMode>);
