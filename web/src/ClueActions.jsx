import {chineseMessage} from './chinese-copy.js'
import {FloatingNotice,InfoBubble} from './Feedback.jsx'
import React,{useEffect,useRef,useState} from 'react'
import {clueApi,reviewNames} from './clue-client.js'

export function CollectionPicker({detail,onUnauthenticated}) {
  const [enabled,setEnabled]=useState(false),[open,setOpen]=useState(false),[choices,setChoices]=useState([]),[selected,setSelected]=useState('')
  const [title,setTitle]=useState(''),[busy,setBusy]=useState(false),[error,setError]=useState(''),[message,setMessage]=useState(''),[cursor,setCursor]=useState(null)
  const createRequest=useRef(null)
  useEffect(()=>{const controller=new AbortController();fetch('/api/v1/features',{signal:controller.signal}).then(r=>r.ok?r.json():{}).then(value=>{if(!controller.signal.aborted)setEnabled(value.clue_collections===true)}).catch(()=>{});return()=>controller.abort()},[])
  async function load(after=null){setBusy(true);setError('');try{const value=await clueApi('?state=open&limit=50'+(after?'&after='+after:''));setChoices(value.items);setSelected(value.items[0]?.collection_id||'');setCursor(value.next_cursor)}catch(e){setError(chineseMessage(e.message));if(e.unauthorized)onUnauthenticated()}finally{setBusy(false)}}
  async function add(event){event.preventDefault();setBusy(true);setError('');setMessage('');try{
    let collection
    if(title.trim()){
      if(createRequest.current?.title!==title.trim())createRequest.current={title:title.trim(),id:crypto.randomUUID()}
      const value=await clueApi('',{method:'POST',body:JSON.stringify({request_id:createRequest.current.id,title:title.trim()})});collection=value.collection
    }else{const value=await clueApi('/'+selected);collection=value.collection;if(value.nodes.some(node=>node.observation_id===detail.observation_id)){setMessage('该线索已在清单中。');return}}
    await clueApi(`/${collection.collection_id}/items/${detail.observation_id}`,{method:'PUT',body:JSON.stringify({expected_revision:collection.revision,observation_version:detail.record_version})})
    setMessage('已加入线索集。');setTitle('');setOpen(false)
  }catch(e){setError(chineseMessage(e.message));if(e.unauthorized)onUnauthenticated()}finally{setBusy(false)}}
  if(!enabled)return null
  return <section className="clue-add"><div className="clue-inline-heading"><button type="button" className="admin-button secondary" onClick={()=>{setOpen(v=>!v);if(!open)load()}} disabled={busy}>加入线索集</button><a className="text-button" href="/clues">打开线索工作区</a></div>{message&&<FloatingNotice role="status" className="admin-success">{message}</FloatingNotice>}{open&&<form onSubmit={add}><label htmlFor="collection-choice">选择已有清单</label><select id="collection-choice" value={selected} onChange={event=>{setSelected(event.target.value);setTitle('')}} disabled={busy}><option value="">请选择</option>{choices.map(value=><option value={value.collection_id} key={value.collection_id}>{value.title}</option>)}</select>{cursor&&<button type="button" className="text-button" onClick={()=>load(cursor)} disabled={busy}>下一页清单</button>}<label htmlFor="new-collection-title">或新建清单</label><input id="new-collection-title" value={title} onChange={event=>setTitle(event.target.value)} maxLength={160} placeholder="输入清单名称"/><button type="submit" className="admin-button" disabled={busy||(!selected&&!title.trim())}>{busy?'正在保存…':'加入清单'}</button></form>}{error&&<FloatingNotice className="error" role="alert">{error}</FloatingNotice>}</section>
}

export function ReviewPanel({context,detail,digest,onUnauthenticated}) {
  const [state,setState]=useState(context.node.review_stale?'pending':context.node.review_state),[note,setNote]=useState(context.node.review_note||'')
  const [busy,setBusy]=useState(false),[error,setError]=useState('')
  const canReview=detail.evidence?.available&&digest==='valid'
  async function submit(event){event.preventDefault();setBusy(true);setError('');try{
    const value=await clueApi(`/${context.collection.collection_id}/items/${detail.observation_id}/review`,{method:'PUT',body:JSON.stringify({expected_revision:context.collection.revision,observation_version:detail.record_version,evidence_sha256:state==='pending'?null:detail.evidence.sha256,state,note})});context.onSaved(value)
  }catch(e){setError(chineseMessage(e.message));if(e.unauthorized)onUnauthenticated()}finally{setBusy(false)}}
  return <section className="clue-review"><h3 className="help-heading">业务线索复核{context.node.review_stale&&<InfoBubble title="查看警告说明" className="error">原复核对应较早版本，请核对当前画面后重新保存。</InfoBubble>}</h3><form onSubmit={submit}><label htmlFor="review-state">复核结果</label><select id="review-state" value={state} onChange={event=>setState(event.target.value)}>{Object.entries(reviewNames).map(([key,label])=><option key={key} value={key}>{label}</option>)}</select><label htmlFor="review-note">复核意见</label><textarea id="review-note" value={note} onChange={event=>setNote(event.target.value)} maxLength={2000} rows={3}/><p className="admin-muted">复核保留当前记录版本与关键帧摘要。</p><button className="admin-button" type="submit" disabled={busy||(state!=='pending'&&!canReview)}>{busy?'正在保存…':'保存复核'}</button>{!canReview&&<p className="admin-muted">读取并校验原图后，可保留或排除该线索。</p>}{error&&<FloatingNotice className="error" role="alert">{error}</FloatingNotice>}</form></section>
}
