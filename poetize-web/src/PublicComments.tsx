import {useEffect,useState,type FormEvent} from 'react';
import {request,type Page} from './api';

type CommentKind='article'|'message'|'love';
const commentPath=(kind:CommentKind)=>kind==='article'?'/api/v2/comments':kind==='love'?'/api/v2/love-comments':'/api/v2/message-comments';
type PublicComment={id:number;user_id:number|null;username:string|null;avatar:string|null;comment_content:string;create_time:string|null;parent_comment_id:number|null;parent_username:string|null;floor_comment_id:number|null;reply_count:number};
type MemberSession={id:number;username:string;csrf_token:string};
function safeAvatar(value:string|null){if(!value)return null;try{const url=new URL(value,window.location.origin);return ['http:','https:'].includes(url.protocol)?url.href:null;}catch{return null;}}

function CommentEntry({item,member,onReply,onRemove}:{item:PublicComment;member:MemberSession|null;onReply:(item:PublicComment)=>void;onRemove:(id:number)=>void}){
  return <article className="public-comment"><div className="public-comment-head">{safeAvatar(item.avatar)&&<img src={safeAvatar(item.avatar)||''} alt=""/>}<div><strong>{item.username||`用户 ${item.user_id??'访客'}`}</strong><small>{item.create_time||'最近'}</small></div></div><p>{item.parent_username&&<span className="comment-mention">@{item.parent_username} </span>}{item.comment_content}</p>{member&&<div className="comment-actions"><button onClick={()=>onReply(item)}>回复</button>{member.id===item.user_id&&<button onClick={()=>onRemove(item.id)}>删除</button>}</div>}</article>;
}
function CommentReplies({root,member,refresh,kind,onReply,onRemove}:{root:PublicComment;member:MemberSession|null;refresh:number;kind:CommentKind;onReply:(item:PublicComment)=>void;onRemove:(id:number)=>void}){
  const [page,setPage]=useState(1);const [result,setResult]=useState<Page<PublicComment>|null>(null);const [items,setItems]=useState<PublicComment[]>([]);
  useEffect(()=>{if(root.reply_count===0){setItems([]);setResult(null);return;}const controller=new AbortController();const path=commentPath(kind);void request<Page<PublicComment>>(`${path}/${root.id}/replies?page=${page}&size=5`,{signal:controller.signal}).then(value=>{if(!controller.signal.aborted){setResult(value);setItems(current=>page===1?value.items:[...current,...value.items]);}}).catch(()=>{});return()=>controller.abort();},[root.id,root.reply_count,page,refresh,kind]);
  if(root.reply_count===0)return null;
  return <div className="comment-replies">{items.map(item=><CommentEntry key={item.id} item={item} member={member} onReply={onReply} onRemove={onRemove}/>)}{result&&items.length<result.total&&<button type="button" className="comment-more" onClick={()=>setPage(value=>value+1)}>展开剩余 {result.total-items.length} 条回复</button>}</div>;
}

export function PublicComments({articleId,kind='article'}:{articleId?:number;kind?:CommentKind}){
  const [comments,setComments]=useState<Page<PublicComment>|null>(null);
  const [member,setMember]=useState<MemberSession|null>(null);
  const [draft,setDraft]=useState('');const [replyTo,setReplyTo]=useState<PublicComment|null>(null);
  const [failure,setFailure]=useState('');const [refresh,setRefresh]=useState(0);const [page,setPage]=useState(1);
  const path=commentPath(kind);
  useEffect(()=>{const controller=new AbortController();const filter=kind==='article'?`&article_id=${articleId}`:'';void request<Page<PublicComment>>(`${path}?page=${page}&size=10${filter}`,{signal:controller.signal}).then(setComments).catch(()=>{if(!controller.signal.aborted)setFailure('评论加载失败');});void request<MemberSession>('/api/v2/members/session',{signal:controller.signal}).then(setMember).catch(()=>setMember(null));return()=>controller.abort();},[articleId,kind,path,page,refresh]);
  async function post(event:FormEvent){event.preventDefault();if(!member)return;setFailure('');try{const body=kind==='article'?{article_id:articleId,content:draft,parent_comment_id:replyTo?.id??null}:{content:draft,parent_comment_id:replyTo?.id??null};await request(path,{method:'POST',body:JSON.stringify(body)},member.csrf_token);setDraft('');setReplyTo(null);setPage(1);setRefresh(value=>value+1);}catch(reason){setFailure(reason instanceof Error?reason.message:'评论发表失败');}}
  async function remove(id:number){if(!member||!window.confirm('确定删除评论？'))return;try{await request(`${path}/${id}`,{method:'DELETE'},member.csrf_token);setRefresh(value=>value+1);}catch{setFailure('删除评论失败');}}
  return <section className="article-comments"><h3>Comments | {comments?.total??0} 条评论</h3>{failure&&<p role="alert">{failure}</p>}{comments?.items.map(item=><div className="comment-floor" key={item.id}><CommentEntry item={item} member={member} onReply={setReplyTo} onRemove={id=>void remove(id)}/><CommentReplies root={item} member={member} refresh={refresh} kind={kind} onReply={setReplyTo} onRemove={id=>void remove(id)}/></div>)}{comments?.items.length===0&&<p className="muted">还没有评论。</p>}{comments&&comments.total>10&&<div className="pager"><button disabled={page<=1} onClick={()=>setPage(value=>value-1)}>上一页</button><span>第 {page} 页</span><button disabled={page*10>=comments.total} onClick={()=>setPage(value=>value+1)}>下一页</button></div>}{member?<form className="comment-form" onSubmit={event=>void post(event)}>{replyTo&&<button type="button" className="text-action" onClick={()=>setReplyTo(null)}>回复 {replyTo.username||`#${replyTo.id}`} · 取消</button>}<textarea required maxLength={1024} value={draft} onChange={event=>setDraft(event.target.value)} placeholder="写下你的想法"/><button type="submit">发表评论</button></form>:<p><a href="/im">登录后参与评论</a></p>}</section>;
}
