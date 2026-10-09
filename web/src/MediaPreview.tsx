import { t } from '@xcss/admin-ui/i18n';
import {useEffect} from 'react';

export function safeImageUrl(value:string|null|undefined){
  if(!value)return null;
  try{const url=new URL(value,window.location.origin);return ['http:','https:','blob:'].includes(url.protocol)?url.href:null;}
  catch{return null;}
}

export function ImageLightbox({src,onClose}:{src:string|null;onClose:()=>void}){
  useEffect(()=>{
    if(!src)return;
    const close=(event:KeyboardEvent)=>{if(event.key==='Escape')onClose();};
    document.addEventListener('keydown',close);
    return()=>document.removeEventListener('keydown',close);
  },[src,onClose]);
  if(!src)return null;
  return <div className="image-lightbox" role="dialog" aria-modal="true" aria-label={t("查看图片", "View image")} onMouseDown={event=>{if(event.target===event.currentTarget)onClose();}}><button type="button" className="image-lightbox-close" onClick={onClose} aria-label={t("关闭图片", "Close image")}>×</button><img src={src} alt={t("放大的图片", "Enlarged image")} /></div>;
}
