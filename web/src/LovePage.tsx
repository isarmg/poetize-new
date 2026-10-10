import { isFamilies, isLinkClasses, isLinkPage, isNotePage, type Family, type LinkClass, type Note } from './public-contracts';
import { t } from '@xcss/web/admin-ui/i18n';
import { useEffect, useRef, useState } from 'react';
import { publicErrorMessage, request, type Page } from './api';
import { ImageLightbox, safeImageUrl } from './MediaPreview';
import { PhotoGrid, type PhotoItem } from './PhotoGallery';
import { PublicChrome } from './PublicChrome';
import { PublicComments } from './PublicComments';

type Tab = 'notes' | 'photos' | 'wishes' | 'families';

function dateValue(value: string | null | undefined) {
  if (!value) return NaN;
  return Date.parse(/^\d{4}-\d{2}-\d{2}$/.test(value) ? value + 'T00:00:00' : value.replace(' ', 'T'));
}
function dateLabel(value: number) {
  return new Intl.DateTimeFormat(document.documentElement.lang || 'zh-CN', { year: 'numeric', month: 'long', day: 'numeric' }).format(value);
}
function Avatar({ name, src }: { name: string; src: string | null | undefined }) {
  return <span className="love-avatar">{safeImageUrl(src) ? <img src={safeImageUrl(src)!} alt="" />
    : <span aria-hidden="true">{Array.from(name)[0]}</span>}</span>;
}
function Feedback({ message, retry }: { message: string; retry: () => void }) {
  return <div className="public-feedback" role="alert"><p>{message}</p><button type="button" onClick={retry}>{t('重试', 'Try again')}</button></div>;
}

export function LovePage() {
  const [families, setFamilies] = useState<Family[] | null>(null);
  const [selected, setSelected] = useState<number | null>(null);
  const [familyFailure, setFamilyFailure] = useState('');
  const [familyVersion, setFamilyVersion] = useState(0);
  const [tab, setTab] = useState<Tab>('photos');
  const [now, setNow] = useState(Date.now());
  const [notes, setNotes] = useState<Page<Note> | null>(null);
  const [notePage, setNotePage] = useState(1);
  const [noteBusy, setNoteBusy] = useState(false);
  const [noteFailure, setNoteFailure] = useState('');
  const [noteVersion, setNoteVersion] = useState(0);
  const [classes, setClasses] = useState<LinkClass[]>([]);
  const [classFailure, setClassFailure] = useState('');
  const [classify, setClassify] = useState('');
  const [photoPage, setPhotoPage] = useState(1);
  const [photos, setPhotos] = useState<PhotoItem[]>([]);
  const [photoTotal, setPhotoTotal] = useState<number | null>(null);
  const [allPhotoTotal, setAllPhotoTotal] = useState<number | null>(null);
  const [photoBusy, setPhotoBusy] = useState(true);
  const [photoFailure, setPhotoFailure] = useState('');
  const [photoVersion, setPhotoVersion] = useState(0);
  const [lightbox, setLightbox] = useState<string | null>(null);
  const [familyOffset, setFamilyOffset] = useState(0);
  const content = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const controller = new AbortController();
    setFamilyFailure('');
    void request('/api/v1/family', isFamilies, { signal: controller.signal })
      .then(value => { if (!controller.signal.aborted) setFamilies(value); })
      .catch(reason => { if (!controller.signal.aborted) setFamilyFailure(publicErrorMessage(reason, t('故事加载失败', 'Unable to load stories'))); });
    return () => controller.abort();
  }, [familyVersion]);
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, []);
  useEffect(() => {
    if (tab !== 'notes') return;
    const controller = new AbortController();
    setNoteBusy(true); setNoteFailure('');
    void request(`/api/v1/notes/page?page=${notePage}&size=10`, isNotePage, { signal: controller.signal })
      .then(value => { if (!controller.signal.aborted) setNotes(value); })
      .catch(reason => { if (!controller.signal.aborted) setNoteFailure(publicErrorMessage(reason, t('记录加载失败', 'Unable to load entries'))); })
      .finally(() => { if (!controller.signal.aborted) setNoteBusy(false); });
    return () => controller.abort();
  }, [tab, notePage, noteVersion]);
  useEffect(() => {
    const controller = new AbortController(); setClassFailure('');
    void request('/api/v1/links/classes?kind=lovePhoto', isLinkClasses, { signal: controller.signal })
      .then(value => { if (!controller.signal.aborted) setClasses(value); })
      .catch(reason => { if (!controller.signal.aborted) setClassFailure(publicErrorMessage(reason, t('相册分类加载失败', 'Unable to load photo categories'))); });
    return () => controller.abort();
  }, [photoVersion]);
  useEffect(() => {
    if (tab !== 'photos') return;
    const controller = new AbortController(); setPhotoBusy(true); setPhotoFailure('');
    const filter = classify ? `&classify=${encodeURIComponent(classify)}` : '';
    void request(`/api/v1/links/page?kind=lovePhoto&page=${photoPage}&size=12${filter}`, isLinkPage, { signal: controller.signal })
      .then(value => {
        if (controller.signal.aborted) return;
        setPhotoTotal(value.total);
        if (!classify) setAllPhotoTotal(value.total);
        setPhotos(current => photoPage === 1 ? value.items : [...current, ...value.items.filter(item => !current.some(existing => existing.id === item.id))]);
      })
      .catch(reason => { if (!controller.signal.aborted) setPhotoFailure(publicErrorMessage(reason, t('相册加载失败', 'Unable to load photos'))); })
      .finally(() => { if (!controller.signal.aborted) setPhotoBusy(false); });
    return () => controller.abort();
  }, [tab, classify, photoPage, photoVersion]);

  const love = families?.find(item => item.id === selected) || families?.[0];
  const hisName = love?.man_name || t('他', 'Him'), herName = love?.woman_name || t('她', 'Her');
  const since = dateValue(love?.timing);
  const seconds = Number.isFinite(since) && since <= now ? Math.floor((now - since) / 1000) : null;
  const countdown = dateValue(love?.countdown_time);
  const target = new Date(countdown), current = new Date(now);
  const left = Number.isFinite(countdown) ? Math.round((Date.UTC(target.getFullYear(), target.getMonth(), target.getDate()) - Date.UTC(current.getFullYear(), current.getMonth(), current.getDate())) / 86400000) : null;
  const tabs: { id: Tab; label: string; icon: string; description: string }[] = [
    { id: 'notes', label: t('点点滴滴', 'Moments'), icon: '✍️', description: t('日常的文字记录', 'Everyday entries') },
    { id: 'photos', label: t('时光相册', 'Photos'), icon: '📸', description: t('照片与相册分类', 'Photos and collections') },
    { id: 'wishes', label: t('祝福板', 'Wishes'), icon: '💌', description: t('收下朋友的祝福', 'Wishes from friends') },
    { id: 'families', label: t('表白墙', 'Love wall'), icon: '🚗', description: t('遇见更多人的故事', 'Meet other couples') },
  ];
  function chooseClass(value: string) { setClassify(value); setPhotoPage(1); setPhotos([]); setPhotoTotal(null); }
  function showTab(value: Tab) { setTab(value); content.current?.scrollIntoView({ behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth', block: 'start' }); }

  return <PublicChrome plainHeader title={t('恋爱笔记', 'Love journal')}>
    <div className="love-page">
      <section className="love-hero" style={safeImageUrl(love?.bg_cover) ? { backgroundImage: `url(${JSON.stringify(safeImageUrl(love?.bg_cover))})` } : undefined}>
        <div className="love-hero-inner">
          <div className="love-hero-copy"><p className="love-kicker">{t('家 · 恋爱笔记', 'HOME · LOVE JOURNAL')}</p>
            <h1>{t('这是我们一起走过的', 'Our journey together')}</h1>
            <p>{t('记录相伴的日子，珍藏照片，也收下朋友们的祝福。', 'Keep the days we share, the photos we treasure, and the wishes from our friends.')}</p>
            <div className="love-hero-actions"><button type="button" onClick={() => showTab('families')}>{t('看看表白墙', 'Explore the love wall')} <span aria-hidden="true">↗</span></button></div>
          </div>
          <div className="love-pair-card"><div className="love-pair">
            <div><Avatar name={hisName} src={love?.man_cover} /><strong>{hisName}</strong></div><span className="love-heart" aria-hidden="true">♡</span>
            <div><Avatar name={herName} src={love?.woman_cover} /><strong>{herName}</strong></div>
          </div><p>{Number.isFinite(since) ? <>{t('故事开始于 ', 'Our story began on ')}<time dateTime={love?.timing || undefined}>{dateLabel(since)}</time></> : t('等待第一份属于我们的回忆', 'A place for our first memory together')}</p>
            {(families?.length || 0) > 1 && <label className="love-story-picker"><span id="love-story-label">{t('切换故事', 'Choose a story')}</span>
              <select aria-labelledby="love-story-label" value={love?.id} onChange={event => setSelected(Number(event.target.value))}>{families?.map(item => <option key={item.id} value={item.id}>{item.man_name || t('他', 'Him')} · {item.woman_name || t('她', 'Her')}</option>)}</select></label>}
          </div>
        </div>
      </section>
      <div className="love-content">
        {familyFailure && <Feedback message={familyFailure} retry={() => setFamilyVersion(value => value + 1)} />}
        <div className="love-overview">
          <section className="love-timer"><div className="love-section-heading"><h2>{t('相伴的时间', 'Time together')}</h2><span>{t('每一天都值得记住', 'Every day is worth keeping')}</span></div>
            {seconds !== null ? <div className="love-clock" aria-label={t('相伴时长', 'Time spent together')}>
              {[[Math.floor(seconds / 86400), t('天', 'Days')], [Math.floor(seconds / 3600) % 24, t('时', 'Hours')], [Math.floor(seconds / 60) % 60, t('分', 'Minutes')], [seconds % 60, t('秒', 'Seconds')]].map(([value, label]) => <div key={label}><strong>{value.toLocaleString()}</strong><span>{label}</span></div>)}
            </div> : <div className="love-timer-empty"><strong>{Number.isFinite(since) ? t('新的旅程即将开始', 'A new journey begins soon') : t('从第一份回忆开始', 'Start with your first memory')}</strong><p>{t('留下相识的日期，让时间替我们记住每一个日子。', 'Add the day you met and keep track of each day together.')}</p></div>}
            {left !== null && <p className="love-countdown"><span>{love?.countdown_title || t('下一件值得期待的事', 'Something to look forward to')}</span><strong>{left > 0 ? t('还有 {0} 天', left === 1 ? '{0} day to go' : '{0} days to go', [left]) : left === 0 ? t('就是今天', 'Today is the day') : t('已过去 {0} 天', left === -1 ? '{0} day ago' : '{0} days ago', [-left])}</strong></p>}
          </section>
          <section className="love-story-card"><h2>{t('我们的故事', 'Our story')}</h2><p>{love?.family_info || t('把平常的日子写下来，让照片和文字慢慢填满这个家。', 'Make a home for everyday moments, one photo and one story at a time.')}</p>
            <a href="/jotting">{t('看看日常记录', 'Explore our journal')} <span aria-hidden="true">↗</span></a>
          </section>
        </div>
        <div ref={content} className="love-workspace">
          <nav className="love-tabs" aria-label={t('恋爱笔记内容', 'Love journal content')}>
            {tabs.map(item => <button key={item.id} type="button" className={tab === item.id ? 'active' : ''} aria-pressed={tab === item.id} aria-controls="love-panel" onClick={() => setTab(item.id)}>
              <span className="love-tab-icon" aria-hidden="true">{item.icon}</span><span><strong>{item.label}</strong><small>{item.description}</small></span><span className="love-tab-arrow" aria-hidden="true">↗</span>
            </button>)}
          </nav>
          <section id="love-panel" className="love-panel" aria-label={tabs.find(item => item.id === tab)?.label} aria-busy={tab === 'photos' ? photoBusy : tab === 'notes' ? noteBusy : false}>
            {tab === 'photos' && <>
              <div className="love-section-heading"><div><h2>{classify || t('时光相册', 'Photo album')}</h2><p>{t('收藏一起看过的风景。', 'Keep the places and moments we have shared.')}</p></div>{photoTotal !== null && <span>{t('{0} 张照片', photoTotal === 1 ? '{0} photo' : '{0} photos', [photoTotal])}</span>}</div>
              <div className="original-tag-panel love-photo-filters" aria-label={t('相册分类', 'Photo categories')}><button type="button" aria-pressed={!classify} className={!classify ? 'active' : ''} onClick={() => chooseClass('')}>{t('全部', 'All')} <small>{allPhotoTotal ?? (classes.length ? classes.reduce((sum, item) => sum + item.count, 0) : null)}</small></button>{classes.map(item => <button type="button" key={item.classify} aria-pressed={classify === item.classify} className={classify === item.classify ? 'active' : ''} onClick={() => chooseClass(item.classify)}>{item.classify} <small>{item.count}</small></button>)}</div>
              {(photoFailure || classFailure) && <Feedback message={photoFailure || classFailure} retry={() => setPhotoVersion(value => value + 1)} />}
              {photoBusy && !photos.length ? <p className="public-loading" role="status">{t('正在加载照片…', 'Loading photos…')}</p> : (photos.length > 0 || !photoFailure) && <PhotoGrid items={photos} onPreview={setLightbox} />}
              {photoTotal !== null && photos.length < photoTotal && <button type="button" className="travel-more" disabled={photoBusy} onClick={() => setPhotoPage(value => value + 1)}>{photoBusy ? t('加载中…', 'Loading…') : t('加载更多照片', 'Load more photos')}</button>}
            </>}
            {tab === 'notes' && <>
              <div className="love-section-heading"><div><h2>{t('点点滴滴', 'Moments')}</h2><p>{t('记录那些平常又特别的小事。', 'A place for the little things that make our days.')}</p></div><a href="/jotting">{t('浏览更多记录', 'Browse more entries')} <span aria-hidden="true">↗</span></a></div>
              {noteFailure && <Feedback message={noteFailure} retry={() => setNoteVersion(value => value + 1)} />}
              {noteBusy ? <p className="public-loading" role="status">{t('正在加载记录…', 'Loading entries…')}</p> : notes && <div className="love-moments-grid">{notes.items.map(item => <article className="love-moment-card" key={item.id}><small>{item.username || t('站长', 'Site owner')} · {item.create_time || t('最近', 'Recently')}</small><p>{item.content}</p>{safeImageUrl(item.image_path) && <button type="button" className="wall-photo-button" onClick={() => setLightbox(safeImageUrl(item.image_path))} aria-label={t('放大图片', 'Enlarge image')}><img src={safeImageUrl(item.image_path)!} alt={t('微言图片', 'Post image')} loading="lazy" /></button>}</article>)}{notes.items.length === 0 && <div className="public-empty-state"><span aria-hidden="true">✎</span><h3>{t('还没有记录。', 'No entries yet.')}</h3><p>{t('写下此刻的想法，留住每一个值得纪念的日常。', 'Write a thought and keep a little piece of today.')}</p></div>}</div>}
              {notes && notes.total > notes.size && <nav className="pager" aria-label={t('记录分页', 'Entry pages')}><button type="button" disabled={noteBusy || notePage <= 1} onClick={() => setNotePage(value => value - 1)}>{t('上一页', 'Previous page')}</button><span>{t('第 {0} 页', 'Page {0}', [notePage])}</span><button type="button" disabled={noteBusy || notePage * notes.size >= notes.total} onClick={() => setNotePage(value => value + 1)}>{t('下一页', 'Next page')}</button></nav>}
            </>}
            {tab === 'wishes' && <div className="love-wishes"><PublicComments kind="love" title={t('祝福板', 'Wishes')} /></div>}
            {tab === 'families' && <>
              <div className="love-section-heading"><div><h2>{t('表白墙', 'Love wall')}</h2><p>{t('每一份相伴，都有自己的故事。', 'Every couple has a story to share.')}</p></div>{families && <span>{t('{0} 份故事', families.length === 1 ? '{0} story' : '{0} stories', [families.length])}</span>}</div>
              {!families && !familyFailure && <p className="public-loading" role="status">{t('正在加载故事…', 'Loading stories…')}</p>}
              <div className="family-grid">{families?.slice(familyOffset, familyOffset + 12).map(item => <button type="button" key={item.id} className="family-card" aria-label={t('查看 {0} 与 {1} 的故事', 'View the story of {0} and {1}', [item.man_name || t('他', 'Him'), item.woman_name || t('她', 'Her')])} onClick={() => { setSelected(item.id); setTab('photos'); window.scrollTo({ top: 0, behavior: 'smooth' }); }}>
                <div className="family-card-pair"><Avatar name={item.man_name || t('他', 'Him')} src={item.man_cover} /><span>{item.man_name || t('他', 'Him')}</span><b aria-hidden="true">♡</b><Avatar name={item.woman_name || t('她', 'Her')} src={item.woman_cover} /><span>{item.woman_name || t('她', 'Her')}</span></div><p>{item.family_info || t('记录两个人的日常。', 'A home for everyday moments together.')}</p><small>{item.timing || t('故事刚刚开始', 'The story is just beginning')}</small>
              </button>)}</div>
              {families?.length === 0 && <div className="public-empty-state"><span aria-hidden="true">♡</span><h3>{t('表白墙还没有故事', 'No stories on the love wall yet')}</h3><p>{t('新的故事正在路上。', 'New stories are on their way.')}</p></div>}
              {(families?.length || 0) > 12 && <nav className="pager" aria-label={t('故事分页', 'Story pages')}><button type="button" disabled={familyOffset === 0} onClick={() => setFamilyOffset(value => value - 12)}>{t('上一页', 'Previous page')}</button><span>{t('第 {0} 页', 'Page {0}', [Math.floor(familyOffset / 12) + 1])}</span><button type="button" disabled={familyOffset + 12 >= (families?.length || 0)} onClick={() => setFamilyOffset(value => value + 12)}>{t('下一页', 'Next page')}</button></nav>}
            </>}
          </section>
        </div>
      </div>
    </div>
    <ImageLightbox src={lightbox} onClose={() => setLightbox(null)} />
  </PublicChrome>;
}
