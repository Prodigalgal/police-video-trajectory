import React,{useEffect,useId,useLayoutEffect,useRef,useState} from 'react'
import {createPortal} from 'react-dom'
import {feedbackDuration,feedbackKind,popoverPosition} from './feedback.js'

const layers=new WeakMap()
function acquireLayer(owner){
  let layer=layers.get(owner)
  if(!layer){
    const node=owner.ownerDocument.createElement('div')
    node.className='notification-layer'+(owner===owner.ownerDocument.body?' page-notification-layer':'')
    node.setAttribute('aria-label','操作通知');owner.appendChild(node)
    layer={node,users:0};layers.set(owner,layer)
  }
  layer.users+=1
  return {node:layer.node,release(){layer.users-=1;if(layer.users===0){layer.node.remove();layers.delete(owner)}}}
}

function inspect(children){
  let text='',actions=false
  React.Children.forEach(children,child=>{
    if(typeof child==='string'||typeof child==='number'){text+=String(child);return}
    if(!React.isValidElement(child))return
    const nested=inspect(child.props.children);text+=nested.text
    actions=actions||nested.actions||child.type==='button'||child.type==='a'&&Boolean(child.props.href)
  })
  return {text,actions}
}

export function FloatingNotice({children,className='',role='status',kind:requestedKind,duration,onDismiss,id,dismissible=true,messageKey}){
  const anchor=useRef(null),panel=useRef(null),previousFocus=useRef(null),pointerInside=useRef(false),[host,setHost]=useState(null),[closed,setClosed]=useState(false),[paused,setPaused]=useState(false)
  const kind=feedbackKind(className,role,requestedKind),content=inspect(children),identity=kind+':'+(messageKey??content.text)
  const timeout=duration??feedbackDuration(kind,content.actions)
  useLayoutEffect(()=>{
    // A dialog's notification actions must remain reachable by its existing focus trap.
    const owner=anchor.current.closest('[role="dialog"]')||document.body,layer=acquireLayer(owner)
    previousFocus.current=document.activeElement;setHost(layer.node)
    return()=>layer.release()
  },[])
  useEffect(()=>{setClosed(false)},[identity])
  useEffect(()=>{
    if(!timeout||paused||closed)return
    const timer=setTimeout(()=>setClosed(true),timeout)
    return()=>clearTimeout(timer)
  },[timeout,identity,paused,closed])
  function dismiss(){
    const ownedFocus=panel.current?.contains(document.activeElement)
    setClosed(true);onDismiss?.()
    if(ownedFocus&&previousFocus.current?.isConnected)previousFocus.current.focus?.({preventScroll:true})
  }
  return <><span ref={anchor} hidden aria-hidden="true"/>{host&&!closed&&createPortal(<aside ref={panel} id={id} className={'floating-notice '+kind} role={kind==='error'||kind==='warning'?'alert':'status'} aria-label={{error:'错误提示',warning:'操作提醒',success:'操作成功',info:'操作提示'}[kind]} onPointerEnter={()=>{pointerInside.current=true;setPaused(true)}} onPointerLeave={()=>{pointerInside.current=false;setPaused(panel.current?.contains(document.activeElement)===true)}} onFocusCapture={()=>setPaused(true)} onBlurCapture={event=>{if(!event.currentTarget.contains(event.relatedTarget))setPaused(pointerInside.current)}} onKeyDown={event=>{if(event.key==='Escape'&&dismissible){event.preventDefault();event.stopPropagation();dismiss()}}}>
    <svg className="feedback-indicator" viewBox="0 0 20 20" fill="none" aria-hidden="true"><path d={kind==='success'?'m5 10 3.5 3.5L15 6':kind==='info'?'M10 9v6M10 5v.5':'M10 5v6M10 14v.5'} stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"/></svg><div className="feedback-content">{children}</div>{dismissible&&<button type="button" className="feedback-close" onClick={dismiss} aria-label="关闭通知">×</button>}
  </aside>,host)}</>
}

export function InfoBubble({children,title='提示说明',className=''}){
  const anchor=useRef(null),panel=useRef(null),id=useId(),[open,setOpen]=useState(false),[owner,setOwner]=useState(null),[position,setPosition]=useState(null)
  useLayoutEffect(()=>{
    if(!open)return
    setOwner(anchor.current.closest('[role="dialog"]')||document.body)
    setPosition(popoverPosition(anchor.current.getBoundingClientRect(),window.innerWidth,window.innerHeight,panel.current?.getBoundingClientRect().height))
  },[open,owner,children])
  useEffect(()=>{
    if(!open)return
    function close(){setOpen(false)}
    function scroll(event){if(!(event.target instanceof Node)||!panel.current?.contains(event.target))close()}
    function outside(event){if(!anchor.current?.contains(event.target)&&!panel.current?.contains(event.target))close()}
    function key(event){if(event.key==='Escape'){event.preventDefault();event.stopPropagation();close();anchor.current?.focus({preventScroll:true})}}
    document.addEventListener('pointerdown',outside,true);document.addEventListener('keydown',key,true)
    window.addEventListener('resize',close);window.addEventListener('scroll',scroll,true)
    return()=>{document.removeEventListener('pointerdown',outside,true);document.removeEventListener('keydown',key,true);window.removeEventListener('resize',close);window.removeEventListener('scroll',scroll,true)}
  },[open])
  return <><button type="button" ref={anchor} className={'help-trigger'+(/error/.test(className)?' help-warning':'')} aria-label={title} aria-expanded={open} aria-controls={open?id:undefined} aria-describedby={open?id:undefined} onClick={()=>setOpen(value=>!value)}><svg width="18" height="18" viewBox="0 0 24 24" fill="none" aria-hidden="true"><circle cx="12" cy="12" r="9" stroke="currentColor" strokeWidth="1.7"/><path d="M12 11v6M12 7.3v.4" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round"/></svg></button>{open&&owner&&position&&createPortal(<aside id={id} ref={panel} className={'help-popover '+(owner===document.body?'page-popover':'')} style={position} role="note" aria-label={title}><div className="help-popover-heading"><strong>{title}</strong><button type="button" className="feedback-close" aria-label="关闭说明" onClick={()=>{setOpen(false);anchor.current?.focus({preventScroll:true})}}>×</button></div><div className={'help-popover-content '+className}>{children}</div></aside>,owner)}</>
}
