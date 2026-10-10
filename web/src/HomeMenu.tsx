import {useCallback, useEffect, useId, useRef, useState} from 'react';
import {t} from '@xcss/web/admin-ui/i18n';

export function HomeMenu({headerHidden, menuOpen}: {headerHidden: boolean; menuOpen: boolean}) {
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLDivElement>(null);
  const link = useRef<HTMLAnchorElement>(null);
  const popup = useRef<HTMLDivElement>(null);
  const timer = useRef(0), frame = useRef(0);
  const popupId = useId();
  const cancelHover = useCallback(() => { window.clearTimeout(timer.current); timer.current = 0; }, []);
  const close = useCallback(() => {
    cancelHover(); window.cancelAnimationFrame(frame.current); setOpen(false);
  }, [cancelHover]);
  useEffect(() => { close(); }, [headerHidden, menuOpen, close]);
  useEffect(() => {
    const outside = (event: PointerEvent) => {
      if (event.target instanceof Node && !root.current?.contains(event.target)) close();
    };
    document.addEventListener('pointerdown', outside);
    return () => {
      document.removeEventListener('pointerdown', outside);
      cancelHover(); window.cancelAnimationFrame(frame.current);
    };
  }, [close, cancelHover]);
  const arrow = <svg aria-hidden="true" viewBox="0 0 12 12" fill="none" stroke="currentColor" strokeWidth="1.5"><path d="m3 4.5 3 3 3-3"/></svg>;
  return <div ref={root} className="public-sort-menu public-home-menu"
    onPointerEnter={event => {
      if (event.pointerType === 'touch') return;
      cancelHover(); timer.current = window.setTimeout(() => { timer.current = 0; setOpen(true); }, 250);
    }}
    onPointerLeave={close}
    onBlurCapture={event => { if (!(event.relatedTarget instanceof Node) || !event.currentTarget.contains(event.relatedTarget)) close(); }}
    onKeyDown={event => {
      if ((event.key === 'ArrowDown' || event.key === 'ArrowUp') && event.target === link.current) {
        event.preventDefault(); cancelHover(); setOpen(true);
        const last = event.key === 'ArrowUp';
        frame.current = window.requestAnimationFrame(() => {
          const links = popup.current?.querySelectorAll<HTMLAnchorElement>('a');
          (last ? links?.item(links.length - 1) : links?.item(0))?.focus();
        });
      } else if (event.key === 'Escape' && open) {
        event.preventDefault(); event.stopPropagation(); close(); link.current?.focus();
      }
    }}>
    <a ref={link} className="public-home-link" href="/" aria-current={window.location.pathname === '/' ? 'page' : undefined} aria-expanded={open} aria-controls={popupId}>
      <span aria-hidden="true">🏡</span> {t('首页', 'Home')} {arrow}
    </a>
    <button className="public-home-toggle" type="button" aria-label={open ? t('关闭首页菜单', 'Close Home menu') : t('打开首页菜单', 'Open Home menu')} aria-expanded={open} aria-controls={popupId}
      onClick={() => { cancelHover(); setOpen(value => !value); }}>{arrow}</button>
    <div ref={popup} id={popupId} hidden={!open}>
      <a href="/">{t('🏠 博客', '🏠 Blog')}</a>
      <a href="/search">{t('🔍 起始页', '🔍 Start page')}</a>
      <a href="/sort">{t('🗂️ 内容', '🗂️ Content')}</a>
      <a href="/sort">{t('🏷️ 专栏', '🏷️ Columns')}</a>
    </div>
  </div>;
}
