import { FormEvent, KeyboardEvent, PointerEvent, useCallback, useEffect, useRef, useState } from 'react'
import { Activity, Bot, CheckCircle2, Droplets, LoaderCircle, Plus, Send, Sparkles, Trash2, Wrench } from 'lucide-react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { AgentConversation, aiApi, ScheduledTask, ScheduledTaskExecution } from '../api/ai'

type ToolStep = { name: string; resultSummary: string; durationMs: number; status: 'COMPLETED' | 'FAILED' }
type RagReference = { source: string; score?: number | null; excerpt: string }
type AgentTrace = { tools?: ToolStep[]; ragReferences?: RagReference[] }
type Message = { id: string; role: 'user' | 'agent'; content: string; trace?: AgentTrace }

// 部分模型会省略标题后的空格，或把连续标题拼在上一段后面；
// 在交给 react-markdown 前补齐常见 Markdown 分隔符，避免标题被当作普通文本。
const normalizeMarkdown = (content: string) => content
  .replace(/\r\n?/g, '\n')
  // 只拆分粘在正文末尾的标题，不触碰行首已有的标题标记。
  .replace(/([^\n#])(?=#{2,6}\S)/g, '$1\n')
  // 只给没有空格的行首标题补空格，避免把 ## 拆成 # #。
  .split('\n')
  .map((line) => line.replace(/^(#{1,6})(?!#)(?=\S)/, '$1 '))
  .join('\n')

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
  const [conversations, setConversations] = useState<AgentConversation[]>([])
  const [activeConversationId, setActiveConversationId] = useState<string | null>(null)
  const [scheduledTasks, setScheduledTasks] = useState<ScheduledTask[]>([])
  const [selectedScheduledTask, setSelectedScheduledTask] = useState<ScheduledTask | null>(null)
  const [taskExecutions, setTaskExecutions] = useState<ScheduledTaskExecution[]>([])
  const [taskExecutionsLoading, setTaskExecutionsLoading] = useState(false)
  const [sidebarWidth, setSidebarWidth] = useState(260)
  const [resizingSidebar, setResizingSidebar] = useState(false)
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
  const refreshConversations = useCallback(async () => {
    try {
      setConversations(await aiApi.listConversations())
    } catch (error) {
      console.error('Failed to load conversations:', error)
    }
  }, [])
  const refreshScheduledTasks = useCallback(async () => {
    try {
      const tasks = await aiApi.listScheduledTasks()
      setScheduledTasks(tasks)
      setSelectedScheduledTask((current) => current
        ? tasks.find((task) => task.id === current.id) ?? null
        : null)
    } catch (error) {
      console.error('Failed to load scheduled tasks:', error)
    }
  }, [])
  useEffect(() => { void refreshConversations() }, [refreshConversations])
  useEffect(() => {
    void refreshScheduledTasks()
    const timer = window.setInterval(() => void refreshScheduledTasks(), 10_000)
    return () => window.clearInterval(timer)
  }, [refreshScheduledTasks])
  const refreshTaskExecutions = useCallback(async (taskId: string) => {
    setTaskExecutionsLoading(true)
    try {
      setTaskExecutions(await aiApi.listScheduledTaskExecutions(taskId))
    } catch (error) {
      console.error('Failed to load scheduled task executions:', error)
      setTaskExecutions([])
    } finally {
      setTaskExecutionsLoading(false)
    }
  }, [])
  useEffect(() => {
    if (!selectedScheduledTask) return
    void refreshTaskExecutions(selectedScheduledTask.id)
    const timer = window.setInterval(() => void refreshTaskExecutions(selectedScheduledTask.id), 10_000)
    return () => window.clearInterval(timer)
  }, [refreshTaskExecutions, selectedScheduledTask])

  const startNew = async () => {
    if (pending) return
    try {
      const conversation = await aiApi.createConversation()
      setConversations((current) => [conversation, ...current])
      setActiveConversationId(conversation.id)
      setMessages([{ ...welcome, id: crypto.randomUUID() }])
      setInput('')
    } catch (error) {
      console.error('Failed to create conversation:', error)
    }
  }

  const selectConversation = async (conversation: AgentConversation) => {
    if (pending || conversation.id === activeConversationId) return
    try {
      const history = await aiApi.listConversationMessages(conversation.id)
      setActiveConversationId(conversation.id)
      setMessages(history.length === 0
        ? [{ ...welcome, id: crypto.randomUUID() }]
        : history.map((message) => ({
            id: `history-${message.id}`,
            role: message.role === 'assistant' ? 'agent' : 'user',
            content: message.content,
          })))
    } catch (error) {
      console.error('Failed to load conversation:', error)
    }
  }
  const deleteConversation = async (conversation: AgentConversation) => {
    if (pending || !window.confirm(`确定删除会话“${conversation.title}”吗？删除后无法恢复。`)) return
    try {
      await aiApi.deleteConversation(conversation.id)
      setConversations((current) => current.filter((item) => item.id !== conversation.id))
      if (conversation.id === activeConversationId) {
        setActiveConversationId(null)
        setMessages([{ ...welcome, id: crypto.randomUUID() }])
        setInput('')
      }
    } catch (error) {
      console.error('Failed to delete conversation:', error)
      window.alert('删除会话失败，请稍后重试。')
    }
  }
  const deleteScheduledTask = async (task: ScheduledTask) => {
    if (!window.confirm(`确定删除定时任务“${task.taskName}”吗？`)) return
    try {
      await aiApi.deleteScheduledTask(task.id)
      setScheduledTasks((current) => current.filter((item) => item.id !== task.id))
      if (selectedScheduledTask?.id === task.id) {
        setSelectedScheduledTask(null)
        setTaskExecutions([])
      }
    } catch (error) {
      console.error('Failed to delete scheduled task:', error)
      window.alert('删除定时任务失败，请稍后重试。')
    }
  }
  const openScheduledTask = (task: ScheduledTask) => {
    setSelectedScheduledTask(task)
    setTaskExecutions([])
  }
  const ask = async (rawQuestion = input) => {
    const question = rawQuestion.trim()
    if (!question || pending) return
    const agentMessageId = crypto.randomUUID()
    setMessages((current) => [...current, { id: crypto.randomUUID(), role: 'user', content: question }])
    setMessages((current) => [...current, { id: agentMessageId, role: 'agent', content: '' }])
    setInput(''); setPending(true)
    try {
      if (!serviceOnline) throw new Error('无法连接 Agent 后端（localhost:8080）')
      const parameters = new URLSearchParams({ input: question })
      if (activeConversationId) parameters.set('conversationId', activeConversationId)
      const response = await fetch(`/api/ai/chatWithAgent/stream?${parameters}`, {
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
        if (eventName === 'conversation') {
          setActiveConversationId(payload)
          void refreshConversations()
        }
        if (eventName === 'done') {
          completed = true
          void refreshScheduledTasks()
        }
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
      void refreshConversations()
    } catch (error) {
      setMessages((current) => current.map((message) => message.id === agentMessageId ? {
        ...message,
        content: `未获得模型响应：${error instanceof Error ? error.message : '请求失败'}。本次没有生成 AI 结果，请确认后端、Redis、PGVector 与 API_KEY 已启动并可用。`,
      } : message))
    } finally { setPending(false) }
  }
  const submit = (event: FormEvent) => { event.preventDefault(); void ask() }
  const keyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => { if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); void ask() } }
  const updateSidebarWidth = (clientX: number) => setSidebarWidth(Math.min(420, Math.max(220, clientX)))
  const handleSidebarResizeKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
      event.preventDefault()
      setSidebarWidth((current) => Math.min(420, Math.max(220, current + (event.key === 'ArrowRight' ? 16 : -16))))
    }
  }
  return <div className={`shell ${resizingSidebar ? 'is-resizing' : ''}`} style={{ gridTemplateColumns: `${sidebarWidth}px minmax(0, 1fr)` }}>
    <aside className="sidebar"><div className="brand"><span className="brand-icon"><Droplets size={22} /></span><div><strong>Water Agent</strong><small>Campus Water IQ</small></div></div><button className="new-chat" onClick={() => void startNew()} disabled={pending}><Plus size={17} /> 新建对话</button><p className="side-label">当前会话</p><div className="conversation-list">{conversations.length === 0 ? <p className="conversation-empty">暂无会话</p> : conversations.map((conversation) => <div key={conversation.id} className={`conversation ${conversation.id === activeConversationId ? 'active' : ''}`} onClick={() => void selectConversation(conversation)} title={conversation.title} role="button" tabIndex={0} onKeyDown={(event) => { if (event.key === 'Enter') void selectConversation(conversation) }}><Bot size={17} /><span className="conversation-title"><span className="conversation-title-text" onMouseEnter={(event) => { const text = event.currentTarget; const container = text.parentElement; if (container && text.scrollWidth > container.clientWidth) { container.style.setProperty('--title-scroll-distance', `${text.scrollWidth - container.clientWidth}px`); container.classList.add('is-scrolling') } }} onMouseLeave={(event) => { event.currentTarget.parentElement?.classList.remove('is-scrolling') }}>{conversation.title}</span></span><button className="conversation-delete" type="button" aria-label={`删除会话 ${conversation.title}`} title="删除会话" onClick={(event) => { event.stopPropagation(); void deleteConversation(conversation) }} disabled={pending}><Trash2 size={15} /></button></div>)}</div><p className="side-label task-label">定时任务</p><div className="scheduled-task-list">{scheduledTasks.length === 0 ? <p className="conversation-empty">暂无定时任务</p> : scheduledTasks.map((task) => <div className={`scheduled-task ${selectedScheduledTask?.id === task.id ? 'active' : ''}`} key={task.id} title={task.instruction} onClick={() => openScheduledTask(task)} role="button" tabIndex={0} onKeyDown={(event) => { if (event.key === 'Enter') openScheduledTask(task) }}><div className="scheduled-task-main"><strong>{task.taskName}</strong><span>下次：{task.nextRunAt?.replace('T', ' ').slice(0, 16)}</span></div><button className="conversation-delete" type="button" aria-label={`删除定时任务 ${task.taskName}`} title="删除定时任务" onClick={(event) => { event.stopPropagation(); void deleteScheduledTask(task) }}><Trash2 size={14} /></button></div>)}</div><div className="capability"><Sparkles size={17} /><div><strong>Agent 能力</strong><p>识别意图、调用数据工具、组织可执行结论。</p></div></div><div className="sidebar-resizer" role="slider" aria-label="调整侧边栏宽度" aria-valuemin={220} aria-valuemax={420} aria-valuenow={sidebarWidth} tabIndex={0} onKeyDown={handleSidebarResizeKeyDown} onPointerDown={(event: PointerEvent<HTMLDivElement>) => { event.currentTarget.setPointerCapture(event.pointerId); setResizingSidebar(true) }} onPointerMove={(event) => { if (event.currentTarget.hasPointerCapture(event.pointerId)) updateSidebarWidth(event.clientX) }} onPointerUp={(event) => { event.currentTarget.releasePointerCapture(event.pointerId); setResizingSidebar(false) }} /></aside>
    <main className="workspace"><header><div><p className="eyebrow"><span /> AGENT READY</p><h1>校园水务智能对话</h1></div><div className="service-status" style={serviceOnline ? undefined : { borderColor: 'rgba(248,113,113,.35)', color: '#fca5a5', background: 'rgba(248,113,113,.08)' }}><CheckCircle2 size={14} /> {serviceOnline ? 'Agent 服务已就绪' : 'Agent 服务未连接'}</div></header><section className="chat-area"><div className="chat-wrap">
      {messages.length === 1 && <div className="prompt-grid">{prompts.map(({ title, text, icon: Icon }) => <button key={title} onClick={() => void ask(text)}><Icon size={20} /><strong>{title}</strong><span>{text}</span></button>)}</div>}
      {messages.map((message) => <article key={message.id} className={`message ${message.role}`}>{message.role === 'agent' && <div className="avatar"><Bot size={19} /></div>}<div className="bubble">{message.role === 'agent' ? <div className="markdown"><ReactMarkdown remarkPlugins={[remarkGfm]}>{normalizeMarkdown(message.content)}</ReactMarkdown></div> : <p>{message.content}</p>}{message.trace && <details className="tool-trace"><summary>Agent 执行轨迹 · RAG 参考资料</summary><div className="tool-trace-content"><b>Agent 执行轨迹</b>{message.trace.tools?.length ? message.trace.tools.map((tool, index) => <div className="trace-row" key={`${tool.name}-${index}`}><span className={`tool-dot ${tool.status === 'FAILED' ? 'failed' : ''}`} /><code>{tool.name}</code><span className="trace-result">{tool.resultSummary}</span><em>{tool.status === 'COMPLETED' ? `${tool.durationMs} ms` : '失败'}</em></div>) : <p className="trace-empty">本次未调用数据工具</p>}{message.trace.ragReferences?.length ? <><b className="rag-title">RAG 参考资料</b>{message.trace.ragReferences.map((reference, index) => <div className="trace-row rag-reference" key={`${reference.source}-${index}`}><span className="tool-dot rag-dot" /><code>{reference.source}</code><span className="trace-result">{reference.excerpt}</span><em>{reference.score == null ? '已引用' : `相似度 ${reference.score.toFixed(3)}`}</em></div>)}</> : <><b className="rag-title">RAG 参考资料</b><p className="trace-empty">本次未命中知识库内容</p></>}</div></details>}</div></article>)}
      {pending && <div className="thinking"><LoaderCircle size={19} /> Agent 正在理解问题并选择工具…</div>}<div ref={endRef} />
    </div></section><footer><form onSubmit={submit}><textarea value={input} onChange={(event) => setInput(event.target.value)} onKeyDown={keyDown} placeholder="例如：帮我分析龙子湖校区今晚是否存在异常用水…" rows={1} /><button type="submit" disabled={!input.trim() || pending} aria-label="发送消息"><Send size={18} /></button></form><p>Enter 发送 · Shift + Enter 换行</p></footer></main>
    {selectedScheduledTask && <div className="task-history-backdrop" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget) setSelectedScheduledTask(null) }}><section className="task-history-panel" role="dialog" aria-modal="true" aria-labelledby="task-history-title"><header className="task-history-header"><div><p className="eyebrow"><span /> 定时任务</p><h2 id="task-history-title">{selectedScheduledTask.taskName}</h2></div><button className="task-history-close" type="button" onClick={() => setSelectedScheduledTask(null)} aria-label="关闭任务历史">×</button></header><div className="task-history-meta"><span>执行规则：{selectedScheduledTask.cronExpression}</span><span>下次执行：{selectedScheduledTask.nextRunAt?.replace('T', ' ').slice(0, 16)}</span><span>任务指令：{selectedScheduledTask.instruction}</span></div><div className="task-history-list">{taskExecutionsLoading && taskExecutions.length === 0 ? <p className="task-history-empty">正在加载执行记录…</p> : taskExecutions.length === 0 ? <p className="task-history-empty">暂无执行记录</p> : taskExecutions.map((execution) => <article className="task-execution" key={execution.id}><div className="task-execution-heading"><span className={`execution-status ${execution.status.toLowerCase()}`} /> <strong>{execution.status === 'SUCCESS' ? '执行成功' : execution.status === 'FAILED' ? '执行失败' : '执行中'}</strong><time>{execution.startedAt?.replace('T', ' ').slice(0, 19)}</time>{execution.durationMs != null && <em>{execution.durationMs} ms</em>}</div><div className="task-history-result markdown"><ReactMarkdown remarkPlugins={[remarkGfm]}>{normalizeMarkdown(execution.result || '暂无返回结果')}</ReactMarkdown></div></article>)}</div></section></div>}
  </div>
}
