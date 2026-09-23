export type ArticleSummary = {id: number; article_title: string; article_cover: string | null; sort_id: number; label_id: number; sort_name: string | null; label_name: string | null; view_count: number; like_count: number; comment_count: number; recommend_status: number; view_status: number; create_time: string | null; excerpt: string | null; search_snippet: string | null};
export type Article = ArticleSummary & {user_id: number; article_content: string; video_url: string | null; username: string | null; sort_name: string | null; label_name: string | null; comment_status: number; password_required: number; tips: string | null; update_time: string | null};
export type Category = {id: number; sort_name: string; sort_description: string | null; priority: number | null; article_count: number};
export type PublicLabel = {id: number; sort_id: number; label_name: string; label_description: string | null; article_count: number};
export type SiteInfo = {web_name: string | null; web_title: string | null; notices: string | null; footer: string | null; background_image: string | null; avatar: string | null; random_avatar: string | null; random_name: string | null; random_cover: string | null; waifu_json: string | null};
export type Page<T> = {items: T[]; total: number; page: number; size: number};
export function articleExcerpt(value: string | null | undefined, limit = 115): string {
  if (!value) return '';
  const plain = value.replace(/!\[[^\]]*\]\([^)]*\)/g, ' ').replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
    .replace(/<[^>]+>/g, ' ').replace(/[#>*_`~|]/g, ' ').replace(/\s+/g, ' ').trim();
  return plain.length > limit ? `${plain.slice(0, limit).trimEnd()}…` : plain;
}
export type AdminSession = {authenticated: true; user_id: string; username: string; role: 'admin'; csrf_token: string};
export type ArticleInput = {article_title: string; article_content: string; article_cover: string | null; video_url: string | null; sort_id: number; label_id: number; view_status: boolean; recommend_status: boolean; comment_status: boolean; password: string | null; tips: string | null};

export async function request<T>(path: string, options: RequestInit = {}, csrf?: string): Promise<T> {
  const headers = new Headers(options.headers);
  if (options.body !== undefined && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
  if (csrf && options.method && !['GET','HEAD'].includes(options.method.toUpperCase())) headers.set('X-CSRF-Token', csrf);
  const response = await fetch(path, {...options, headers, credentials: 'same-origin', redirect: 'error'});
  const data: unknown = await response.json();
  if (!response.ok) {
    const message = typeof data === 'object' && data !== null && 'error' in data && typeof data.error === 'string' ? data.error : `请求失败 (${response.status})`;
    throw new Error(message);
  }
  return data as T;
}
