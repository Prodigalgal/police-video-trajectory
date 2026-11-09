import { useEffect, useRef } from 'react'

export function useModal(onClose) {
  const panel = useRef(null), close = useRef(onClose)
  close.current = onClose
  useEffect(() => {
    const previous = document.activeElement, overflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    const focusable = () => [...(panel.current?.querySelectorAll('button:not(:disabled),a[href],input:not(:disabled),select:not(:disabled),textarea:not(:disabled),video[controls],[tabindex="0"]') || [])].filter(node => node.getClientRects().length)
    ;(focusable()[0] || panel.current)?.focus()
    function onKey(event) {
      if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); close.current(); return }
      if (event.key !== 'Tab') return
      const items = focusable(), first = items[0], last = items[items.length-1]
      if (!first) { event.preventDefault(); panel.current?.focus(); return }
      if (event.shiftKey && (document.activeElement === first || !panel.current?.contains(document.activeElement))) { event.preventDefault(); last.focus() }
      else if (!event.shiftKey && (document.activeElement === last || !panel.current?.contains(document.activeElement))) { event.preventDefault(); first.focus() }
    }
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('keydown', onKey)
      document.body.style.overflow = overflow
      if (previous?.isConnected) previous.focus()
    }
  }, [])
  return panel
}
