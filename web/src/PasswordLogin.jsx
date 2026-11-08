import {chineseMessage} from './chinese-copy.js'
import {FloatingNotice} from './Feedback.jsx'
import React, { useEffect, useRef, useState } from 'react'

export default function PasswordLogin({ onLogin, context, Brand, responseJson }) {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [answer, setAnswer] = useState('')
  const [challenge, setChallenge] = useState(null)
  const [proof, setProof] = useState(null)
  const [error, setError] = useState('')
  const [verificationError, setVerificationError] = useState('')
  const [busy, setBusy] = useState(false)
  const worker = useRef(null)
  const generation = useRef(0)
  const pending = useRef(null)
  async function refresh() {
    const own = ++generation.current
    worker.current?.terminate(); pending.current?.abort()
    const controller = new AbortController(); pending.current = controller
    setChallenge(null); setProof(null); setAnswer(''); setVerificationError('')
    try {
      const data = await responseJson(await fetch('/api/auth/challenge', { signal: controller.signal }))
      if (own !== generation.current) return
      setChallenge(data)
      const next = new Worker(new URL('./pow.worker.js', import.meta.url), { type: 'module' })
      worker.current = next
      next.onmessage = ({ data: result }) => {
        if (own !== generation.current) return
        if (result.nonce) setProof(result.nonce)
        if (result.error) setVerificationError('安全校验未完成，请刷新验证码重试。')
        next.terminate()
      }
      next.onerror = () => { if (own === generation.current) setVerificationError('安全校验未完成，请刷新验证码重试。'); next.terminate() }
      next.postMessage({ challenge_id: data.challenge_id, bits: data.pow.bits, expires_in: data.expires_in })
    } catch (e) { if (e.name !== 'AbortError' && own === generation.current) setVerificationError(chineseMessage(e.message)) }
  }
  useEffect(() => { refresh(); return () => { ++generation.current; worker.current?.terminate(); pending.current?.abort() } }, [])
  async function submit(event) {
    event.preventDefault()
    if (!challenge || !proof || busy) return
    setBusy(true); setError('')
    try {
      const session = await responseJson(await fetch('/api/session', { method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, password, challenge_id: challenge.challenge_id, captcha_answer: answer.trim(), pow_nonce: proof }) }))
      setPassword(''); onLogin(session)
    } catch (e) { setError(chineseMessage(e.message)); await refresh() } finally { setBusy(false) }
  }
  return <main className="access-wrap"><section className="access-card"><Brand/><div className="access-copy"><p className="eyebrow">受控访问</p><h1>登录视迹</h1><p>登录后进入{context === 'evaluation' ? '评测工作台' : context === 'admin' ? '管理后台' : '视频线索搜索'}。</p></div><form onSubmit={submit}>
    <label htmlFor="username">用户名</label><input id="username" autoComplete="username" value={username} onChange={e => setUsername(e.target.value)} maxLength={80} required disabled={busy}/>
    <label htmlFor="password">密码</label><input id="password" type="password" autoComplete="current-password" value={password} onChange={e => setPassword(e.target.value)} maxLength={128} required disabled={busy}/>
    <div className="verification-heading"><label htmlFor="captcha">数学验证码{challenge ? `：${challenge.question}` : ''}</label><button className="text-button" type="button" onClick={refresh} disabled={busy}>换一题</button></div>
    <input id="captcha" inputMode="numeric" autoComplete="off" value={answer} onChange={e => setAnswer(e.target.value)} maxLength={8} required disabled={busy || !challenge} aria-describedby="verification-status"/>
    <span id="verification-status" className="login-verification" role="status">{verificationError?'校验待重试':proof?'安全校验已完成':'安全校验中…'}</span>{verificationError&&<FloatingNotice kind="error">{verificationError}</FloatingNotice>}
    <button className="primary" type="submit" disabled={busy || !challenge || !proof || !answer.trim()}>{busy ? '正在登录…' : '登录'}</button>
    {error && <FloatingNotice className="error" role="alert">{error}</FloatingNotice>}
  </form></section></main>
}
