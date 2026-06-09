// === SSE Chat Event ===
export interface ChatEvent {
  type: 'token' | 'thinking' | 'tool_call' | 'tool_result' | 'usage' | 'compaction' | 'done' | 'error'
  content?: string
  toolName?: string
  toolInput?: Record<string, unknown>
  toolOutput?: string
  usage?: { inputTokens: number; outputTokens: number }
  error?: string
}

// === Message ===
export interface Message {
  id: string
  role: 'user' | 'assistant' | 'system' | 'tool'
  content: string
  thinking?: string
  toolCalls?: ToolCall[]
  timestamp: number
}

export interface ToolCall {
  name: string
  input: Record<string, unknown>
  output?: string
  status: 'running' | 'success' | 'error'
}

// === Conversation ===
export interface Conversation {
  id: string
  title: string
  updatedAt: number
  createdAt: number
  messageCount?: number
}

// === Time grouping (sidebar) ===
export interface ConvGroup {
  label: string
  conversations: Conversation[]
}

// === MCP Server ===
export interface McpServer {
  id?: string
  name: string
  transport: 'stdio' | 'http'
  command?: string
  args?: string
  envVars?: string
  url?: string
  headers?: string
  status: 'connected' | 'disconnected' | 'error'
  createdAt?: number
  updatedAt?: number
}

// === MCP Tool ===
export interface McpTool {
  id?: string
  serverId: string
  toolName: string
  description: string
  enabled: boolean
  source: 'discovered' | 'manual'
  createdAt?: number
}

// === Skill ===
export interface Skill {
  name: string
  source: 'builtin' | 'community' | 'custom'
  path?: string
  enabled: boolean
}

// === Model ===
export interface ModelConfig {
  id?: string
  name: string
  baseUrl?: string
  apiKey?: string
  isDefault: boolean
}
