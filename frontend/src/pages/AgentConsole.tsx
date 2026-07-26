import { FormEvent, KeyboardEvent, useEffect, useRef, useState } from 'react'
import { Activity, Bot, CheckCircle2, Droplets, LoaderCircle, Plus, Send, Sparkles, Wrench } from 'lucide-react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'

type ToolStep = { name: string; resultSummary: string; durationMs: number; status: 'COMPLETED' | 'FAILED' }
type RagReference = { source: string; score?: number | null; excerpt: string }
type AgentTrace = { tools?: ToolStep[]; ragReferences?: RagReference[] }
type Message = { id: string; role: 'user' | 'agent'; content: string; trace?: AgentTrace }

const prompts = [
  { title: '预测明日用水', text: '预测龙子湖校区明天的用水量，并给出节水建议。', icon: Activity },
  { title: '检查设备状态', text: '查询龙子湖校区 1 号楼 1 层 1 单元水表的运行状态。', icon: Wrench },
  { title: '分析水质', text: '请分析目前的水质合格率，并提供管理建议。', icon: Droplets },
]

const welcome: Message = { id: 'welcome', role: 'agent', content: '你好，我是 Water Agent。可以分析校园用水、查询设备状态、生成水质建议，也可以把复杂问题拆解为多次数据工具调用。' }

export default function AgentConsole() {
  const [messages, setMessages] = useState<Message[]>([welcome])
  const [input, setInput] = useState('')
  const [pending, setPending] = useState(false)
  const [serviceOnline, setServiceOnline] = useState(false)
  const endRef = useRef<HTMLDivElement>(null)
  useEffect(() => { endRef.current?.scrollIntoView({ behavior: 'smooth' }) }, [messages, pending])
  useEffect(() => {
    const checkService = async () => {
      try {
        const response = await fetch('/api/actuator/health')
        setServiceOnline(response.ok)
      } catch {
        setServiceOnline(false)
      }
    }
    void checkService()
    const timer = window.setInterval(() => void checkService(), 10_000)
    return () => window.clearInterval(timer)
  }, [])
  const startNew = () => { setMessages([{ ...welcome, id: crypto.randomUUID() }]); setInput('') }
  const ask = async (rawQuestion = input) => {
    const question = rawQuestion.trim()
    if (!question || pending) return
    const agentMessageId = crypto.randomUUID()
    setMessages((current) => [...current, { id: crypto.randomUUID(), role: 'user', content: question }])
    setMessages((current) => [...current, { id: agentMessageId, role: 'agent', content: '' }])
    setInput(''); setPending(true)
    try {
      if (!serviceOnline) throw new Error('无法连接 Agent 后端（localhost:8080）')
      const response = await fetch(`/api/ai/chatWithAgent/stream?input=${encodeURIComponent(question)}`, {
        method: 'POST', headers: { Accept: 'text/event-stream' },
      })
      if (!response.ok) throw new Error(`Agent 后端返回 HTTP ${response.status}`)
      if (!response.body) throw new Error('浏览器未收到流式响应')
      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''
      let completed = false
      const updateAgent = (updater: (message: Message) => Message) => setMessages((current) =>
        current.map((message) => message.id === agentMessageId ? updater(message) : message),
      )
      const handleEvent = (frame: string) => {
        let eventName = 'message'
        const data: string[] = []
        frame.replace(/\r/g, '').split('\n').forEach((line) => {
          if (line.startsWith('event:')) eventName = line.substring(6).trim()
          // SSE 允许在 "data:" 后带一个可选空格。这里只移除协议分隔符，
          // 因为 Markdown 依赖行首空格与换行。
          if (line.startsWith('data:')) {
            const value = line.substring(5)
            data.push(value.startsWith(' ') ? value.substring(1) : value)
          }
        })
        const payload = data.join('\n')
        if (eventName === 'delta') updateAgent((message) => ({ ...message, content: message.content + payload }))
        if (eventName === 'trace') {
          // 后端会在每次检索或工具事件后发送完整轨迹快照；此处覆盖而非累加，
          // 避免流式页面重复渲染相同节点。
          const trace: AgentTrace = JSON.parse(payload)
          updateAgent((message) => ({ ...message, trace }))
        }
        if (eventName === 'error') throw new Error(payload || '调用模型失败，请稍后重试')
        if (eventName === 'done') completed = true
      }
      while (true) {
        const { done, value } = await reader.read()
        buffer += decoder.decode(value, { stream: !done })
        let boundary = buffer.indexOf('\n\n')
        while (boundary >= 0) {
          handleEvent(buffer.slice(0, boundary))
          buffer = buffer.slice(boundary + 2)
          boundary = buffer.indexOf('\n\n')
        }
        if (done) break
      }
      if (!completed) throw new Error('流式响应意外结束')
    } catch (error) {
      setMessages((current) => current.map((message) => message.id === agentMessageId ? {
        ...message,
        content: `未获得模型响应：${error instanceof Error ? error.message : '请求失败'}。本次没有生成 AI 结果，请确认后端、Redis、PGVector 与 API_KEY 已启动并可用。`,
      } : message))
    } finally { setPending(false) }
  }
  const submit = (event: FormEvent) => { event.preventDefault(); void ask() }
  const keyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => { if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); void ask() } }
  return <div className="shell">
    <aside className="sidebar"><div className="brand"><span className="brand-icon"><Droplets size={22} /></span><div><strong>Water Agent</strong><small>Campus Water IQ</small></div></div><button className="new-chat" onClick={startNew}><Plus size={17} /> 新建对话</button><p className="side-label">当前会话</p><div className="conversation"><Bot size={17} /><span>校园水务智能分析</span></div><div className="capability"><Sparkles size={17} /><div><strong>Agent 能力</strong><p>识别意图、调用数据工具、组织可执行结论。</p></div></div></aside>
    <main className="workspace"><header><div><p className="eyebrow"><span /> AGENT READY</p><h1>校园水务智能对话</h1></div><div className="service-status" style={serviceOnline ? undefined : { borderColor: 'rgba(248,113,113,.35)', color: '#fca5a5', background: 'rgba(248,113,113,.08)' }}><CheckCircle2 size={14} /> {serviceOnline ? 'Agent 服务已就绪' : 'Agent 服务未连接'}</div></header><section className="chat-area"><div className="chat-wrap">
      {messages.length === 1 && <div className="prompt-grid">{prompts.map(({ title, text, icon: Icon }) => <button key={title} onClick={() => void ask(text)}><Icon size={20} /><strong>{title}</strong><span>{text}</span></button>)}</div>}
      {messages.map((message) => <article key={message.id} className={`message ${message.role}`}>{message.role === 'agent' && <div className="avatar"><Bot size={19} /></div>}<div className="bubble">{message.role === 'agent' ? <div className="markdown"><ReactMarkdown remarkPlugins={[remarkGfm]}>{message.content}</ReactMarkdown></div> : <p>{message.content}</p>}{message.trace && <div className="tool-trace"><b>Agent 执行轨迹</b>{message.trace.tools?.length ? message.trace.tools.map((tool, index) => <div className="trace-row" key={`${tool.name}-${index}`}><span className={`tool-dot ${tool.status === 'FAILED' ? 'failed' : ''}`} /><code>{tool.name}</code><span className="trace-result">{tool.resultSummary}</span><em>{tool.status === 'COMPLETED' ? `${tool.durationMs} ms` : '失败'}</em></div>) : <p className="trace-empty">本次未调用数据工具</p>}{message.trace.ragReferences?.length ? <><b className="rag-title">RAG 参考资料</b>{message.trace.ragReferences.map((reference, index) => <div className="trace-row rag-reference" key={`${reference.source}-${index}`}><span className="tool-dot rag-dot" /><code>{reference.source}</code><span className="trace-result">{reference.excerpt}</span><em>{reference.score == null ? '已引用' : `相似度 ${reference.score.toFixed(3)}`}</em></div>)}</> : <><b className="rag-title">RAG 参考资料</b><p className="trace-empty">本次未命中知识库内容</p></>}</div>}</div></article>)}
      {pending && <div className="thinking"><LoaderCircle size={19} /> Agent 正在理解问题并选择工具…</div>}<div ref={endRef} />
    </div></section><footer><form onSubmit={submit}><textarea value={input} onChange={(event) => setInput(event.target.value)} onKeyDown={keyDown} placeholder="例如：帮我分析龙子湖校区今晚是否存在异常用水…" rows={1} /><button type="submit" disabled={!input.trim() || pending} aria-label="发送消息"><Send size={18} /></button></form><p>Enter 发送 · Shift + Enter 换行</p></footer></main>
  </div>
}
