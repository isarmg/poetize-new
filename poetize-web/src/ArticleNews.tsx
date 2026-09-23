import {useEffect,useState,type FormEvent} from 'react';
import {request} from './api';

type News={id:number;user_id:number|null;username:string|null;content:string;create_time:string|null};
type Session={id:number;csrf_token:string};

export function ArticleNews({articleId,authorId}:{articleId:number;authorId:number}){
  const [items,setItems]=useState<News[]>([]);
  const [session,setSession]=useState<Session|null>(null);
  const [draft,setDraft]=useState('');
  const [time,setTime]=useState('');
  const [error,setError]=useState('');
  const [busy,setBusy]=useState(false);
  useEffect(()=>{const controller=new AbortController();void request<News[]>(`/api/v2/articles/${articleId}/news`,{signal:controller.signal}).then(setItems).catch(()=>{if(!controller.signal.aborted)setError('最新进展加载失败');});void request<Session>('/api/v2/members/session',{signal:controller.signal}).then(setSession).catch(()=>{});return()=>controller.abort();},[articleId]);
  async function publish(event:FormEvent){event.preventDefault();if(!session||busy)return;setBusy(true);setError('');try{const saved=await request<News>(`/api/v2/articles/${articleId}/news`,{method:'POST',body:JSON.stringify({content:draft,create_time:time||null})},session.csrf_token);setItems(current=>[saved,...current].sort((a,b)=>(b.create_time||'').localeCompare(a.create_time||'')));setDraft('');setTime('');}catch(reason){setError(reason instanceof Error?reason.message:'发布失败');}finally{setBusy(false);}}
  async function remove(id:number){if(!session||!window.confirm('确定删除这条进展？'))return;try{await request(`/api/v2/articles/${articleId}/news/${id}`,{method:'DELETE'},session.csrf_token);setItems(current=>current.filter(item=>item.id!==id));}catch(reason){setError(reason instanceof Error?reason.message:'删除失败');}}
  if(items.length===0&&session?.id!==authorId&&!error)return null;
  return <section className="article-news"><h2>最新进展</h2>{error&&<p role="alert">{error}</p>}{items.length>0&&<ol>{items.map(item=><li key={item.id}><time>{item.create_time||'最近'}</time><p>{item.content}</p>{session?.id===authorId&&<button type="button" onClick={()=>void remove(item.id)}>删除</button>}</li>)}</ol>}{session?.id===authorId&&<form onSubmit={event=>void publish(event)}><h3>记录进展</h3><textarea required maxLength={1024} value={draft} onChange={event=>setDraft(event.target.value)} placeholder="写下文章的最新进展"/><label>进展时间 <input type="datetime-local" value={time} onChange={event=>setTime(event.target.value)}/></label><button type="submit" disabled={busy||!draft.trim()}>{busy?'发布中…':'发布进展'}</button></form>}</section>;
}
