import React from 'react'

export function BrandMark({className=''}){
  return <svg className={`workspace-mark ${className}`} width="32" height="32" viewBox="0 0 32 32" fill="none" aria-hidden="true"><path d="M11 3H5a2 2 0 0 0-2 2v6M21 3h6a2 2 0 0 1 2 2v6M3 21v6a2 2 0 0 0 2 2h6m10 0h6a2 2 0 0 0 2-2v-6" stroke="currentColor" strokeWidth="3" strokeLinecap="round"/></svg>
}

export function BrandWordmark(){return <><BrandMark/><span>视迹</span></>}

export default function WorkspaceHeader({active='search',isAdmin=false,clues=true,map=true,live=true,children}){
  const links=[['clues','/clues','线索工作区',clues],['map','/map','点位地图',map],['live','/live','实时监控',live],['admin','/admin','管理后台',isAdmin]]
  return <header className="topbar workspace-header"><a className="brand" href="/" aria-label="视迹首页"><BrandWordmark/></a><nav className="workspace-nav" aria-label="主导航">{links.filter(([, , , enabled])=>enabled).map(([key,href,label])=><a href={href} key={key} aria-current={active===key?'page':undefined}>{label}</a>)}</nav><div className="header-tools workspace-account"><details className="workspace-profile-menu"><summary aria-label="账户操作"><svg width="32" height="32" viewBox="0 0 32 32" aria-hidden="true"><circle cx="16" cy="16" r="16" fill="#e7ecf4"/><circle cx="16" cy="11" r="5" fill="#8594ac"/><path d="M6 29v-3a10 10 0 0 1 20 0v3" fill="#8594ac"/></svg></summary><div className="workspace-profile-actions">{children||<a href="/">返回线索搜索</a>}</div></details></div></header>
}
