import { t } from '@xcss/admin-ui/i18n';
import { useEffect, useState, type FormEvent } from 'react';
import { publicErrorMessage, request, type Page } from './api';
import { safeImageUrl } from './MediaPreview';

type CommentKind = 'article' | 'message' | 'love';
type PublicComment = { id: number; user_id: number | null; username: string | null; avatar: string | null; comment_content: string; create_time: string | null; parent_username: string | null; reply_count: number };
const commentPath = (kind: CommentKind) => kind === 'article' ? '/api/v1/comments' : kind === 'love' ? '/api/v1/love-comments' : '/api/v1/message-comments';
const commenter = (item: PublicComment) => item.username || t('匿名访客', 'Anonymous guest');

function CommentEntry({ item, onReply }: { item: PublicComment; onReply: (item: PublicComment) => void }) {
  const avatar = safeImageUrl(item.avatar);
  return <article className="public-comment"><div className="public-comment-head">
    {avatar && <img src={avatar} alt="" />}<div><strong>{commenter(item)}</strong><small>{item.create_time || t('最近', 'Recently')}</small></div>
  </div><p>{item.parent_username && <span className="comment-mention">@{item.parent_username} </span>}{item.comment_content}</p>
    <div className="comment-actions"><button type="button" onClick={() => onReply(item)}>{t('回复', 'Reply')}</button></div>
  </article>;
}

function CommentReplies({ root, refresh, kind, onReply }: { root: PublicComment; refresh: number; kind: CommentKind; onReply: (item: PublicComment) => void }) {
  const [page, setPage] = useState(1);
  const [result, setResult] = useState<Page<PublicComment> | null>(null);
  const [items, setItems] = useState<PublicComment[]>([]);
  const [failure, setFailure] = useState('');
  useEffect(() => setPage(1), [root.id, refresh]);
  useEffect(() => {
    if (!root.reply_count) { setItems([]); setResult(null); return; }
    const controller = new AbortController(); setFailure('');
    void request<Page<PublicComment>>(`${commentPath(kind)}/${root.id}/replies?page=${page}&size=5`, { signal: controller.signal })
      .then(value => { if (!controller.signal.aborted) { setResult(value); setItems(current => page === 1 ? value.items : [...current, ...value.items.filter(item => !current.some(existing => existing.id === item.id))]); } })
      .catch(reason => { if (!controller.signal.aborted) setFailure(publicErrorMessage(reason, t('回复加载失败', 'Unable to load replies'))); });
    return () => controller.abort();
  }, [root.id, root.reply_count, page, refresh, kind]);
  if (!root.reply_count) return null;
  return <div className="comment-replies">{failure && <p role="alert">{failure}</p>}
    {items.map(item => <CommentEntry key={item.id} item={item} onReply={onReply} />)}
    {result && items.length < result.total && <button type="button" className="comment-more" onClick={() => setPage(value => value + 1)}>{t('展开剩余 {0} 条回复', 'Show {0} more replies', [result.total - items.length])}</button>}
  </div>;
}

export function PublicComments({ articleId, kind = 'article', title }: { articleId?: number; kind?: CommentKind; title?: string }) {
  const [comments, setComments] = useState<Page<PublicComment> | null>(null);
  const [draft, setDraft] = useState(''), [replyTo, setReplyTo] = useState<PublicComment | null>(null);
  const [failure, setFailure] = useState(''), [feedback, setFeedback] = useState('');
  const [refresh, setRefresh] = useState(0), [page, setPage] = useState(1), [busy, setBusy] = useState(false);
  const path = commentPath(kind);
  useEffect(() => {
    const controller = new AbortController(); const filter = kind === 'article' ? `&article_id=${articleId}` : '';
    void request<Page<PublicComment>>(`${path}?page=${page}&size=10${filter}`, { signal: controller.signal })
      .then(value => { if (!controller.signal.aborted) setComments(value); })
      .catch(reason => { if (!controller.signal.aborted) setFailure(publicErrorMessage(reason, t('评论加载失败', 'Unable to load comments'))); });
    return () => controller.abort();
  }, [articleId, kind, path, page, refresh]);
  async function post(event: FormEvent) {
    event.preventDefault(); if (busy || !draft.trim()) return;
    setBusy(true); setFailure(''); setFeedback('');
    try {
      const body = { ...(kind === 'article' ? { article_id: articleId } : {}), content: draft.trim(), parent_comment_id: replyTo?.id ?? null };
      await request(path, { method: 'POST', body: JSON.stringify(body) });
      setDraft(''); setReplyTo(null); setPage(1); setRefresh(value => value + 1);
      setFeedback(t('评论已发表。', 'Your comment has been posted.'));
    } catch (reason) { setFailure(publicErrorMessage(reason, t('评论发表失败', 'Unable to publish comment'))); }
    finally { setBusy(false); }
  }
  return <section className="article-comments"><h3>{title || t('评论', 'Comments')} | {t('{0} 条评论', '{0} comments', [comments?.total ?? 0])}</h3>
    {failure && <p role="alert">{failure}</p>}
    {comments?.items.map(item => <div className="comment-floor" key={item.id}><CommentEntry item={item} onReply={setReplyTo} /><CommentReplies root={item} refresh={refresh} kind={kind} onReply={setReplyTo} /></div>)}
    {comments?.items.length === 0 && <p className="muted">{t('还没有评论。', 'No comments yet.')}</p>}
    {comments && comments.total > 10 && <nav className="pager" aria-label={t('评论分页', 'Comment pages')}><button type="button" disabled={busy || page <= 1} onClick={() => setPage(value => value - 1)}>{t('上一页', 'Previous page')}</button><span>{t('第 {0} 页', 'Page {0}', [page])}</span><button type="button" disabled={busy || page * 10 >= comments.total} onClick={() => setPage(value => value + 1)}>{t('下一页', 'Next page')}</button></nav>}
    <form className="comment-form" onSubmit={event => void post(event)}>
      <p className="comment-identity">{t('无需登录，以匿名访客身份发表评论。', 'Post a comment as an anonymous guest. No account is needed.')}</p>
      {replyTo && <button type="button" className="text-action" onClick={() => setReplyTo(null)}>{t('回复 {0} · 取消', 'Reply to {0} · Cancel', [commenter(replyTo)])}</button>}
      <textarea aria-label={t('写下你的想法', 'Write your thoughts')} required maxLength={1024} value={draft} onChange={event => setDraft(event.target.value)} placeholder={t('写下你的想法', 'Write your thoughts')} />
      <button type="submit" disabled={busy || !draft.trim()}>{busy ? t('发表中…', 'Posting…') : t('匿名发表评论', 'Post anonymous comment')}</button>
      {feedback && <p role="status">{feedback}</p>}
    </form>
  </section>;
}
