import { t } from '@xcss/admin-ui/i18n';
import { useEffect, useState } from 'react';
import { publicErrorMessage, request } from './api';

type News = { id: number; content: string; create_time: string | null };

export function ArticleNews({ articleId }: { articleId: number }) {
  const [items, setItems] = useState<News[]>([]), [error, setError] = useState('');
  useEffect(() => {
    const controller = new AbortController();
    void request<News[]>(`/api/v1/articles/${articleId}/news`, { signal: controller.signal })
      .then(value => { if (!controller.signal.aborted) setItems(value); })
      .catch(reason => { if (!controller.signal.aborted) setError(publicErrorMessage(reason, t('最新进展加载失败', 'Unable to load recent updates'))); });
    return () => controller.abort();
  }, [articleId]);
  if (!items.length && !error) return null;
  return <section className="article-news"><h2>{t('最新进展', 'Recent updates')}</h2>{error && <p role="alert">{error}</p>}
    {items.length > 0 && <ol>{items.map(item => <li key={item.id}><time>{item.create_time || t('最近', 'Recently')}</time><p>{item.content}</p></li>)}</ol>}
  </section>;
}
