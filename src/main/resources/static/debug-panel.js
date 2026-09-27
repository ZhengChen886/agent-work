// ==================== 调试面板管理 ====================
const DebugPanel = {
  enabled: false,
  currentConvId: null,
  timelineItems: [],
  toolCalls: [],
  startTime: null,

  init() {
    this.timelineItemsEl = document.getElementById('timelineItems');
    this.toolCallsListEl = document.getElementById('toolCallsList');
    this.toolCallsSection = document.getElementById('toolCallsSection');
    this.debugLogEl = document.getElementById('debugLog');
    this.metricDurationEl = document.getElementById('metricDuration');
    this.metricTokensEl = document.getElementById('metricTokens');
    this.metricToolCallsEl = document.getElementById('metricToolCalls');
  },

  toggle() {
    const panel = document.getElementById('debugPanel');
    panel.classList.toggle('show');
    this.enabled = panel.classList.contains('show');
  },

  start(convId) {
    this.currentConvId = convId;
    this.startTime = Date.now();
    this.timelineItems = [];
    this.toolCalls = [];
    this.renderTimeline();
    this.renderToolCalls();
    this.updateMetrics();
  },

  addStep(stepName, data, status = 'running') {
    const item = {
      stepName,
      duration: data.duration_ms || 0,
      status,
      detail: this.formatDetail(data)
    };
    this.timelineItems.push(item);
    this.renderTimeline();
    this.updateMetrics();
  },

  addToolCall(toolName, args, result, durationMs, status) {
    const toolCall = {
      toolName,
      args: this.formatArgs(args),
      result: typeof result === 'string' ? (result.length > 100 ? result.substring(0, 100) + '...' : result) : JSON.stringify(result),
      durationMs,
      status
    };
    this.toolCalls.push(toolCall);
    this.renderToolCalls();
    this.updateMetrics();
  },

  addLog(type, data) {
    if (!this.debugLogEl) return;
    this.debugLogEl.style.display = 'block';
    const timestamp = new Date().toLocaleTimeString();
    const entry = document.createElement('div');
    entry.className = 'log-entry';
    entry.innerHTML = `<span class="timestamp">[${timestamp}]</span><span class="type">${type}:</span><span class="data">${JSON.stringify(data)}</span>`;
    this.debugLogEl.appendChild(entry);
    this.debugLogEl.scrollTop = this.debugLogEl.scrollHeight;
  },

  formatDetail(data) {
    const parts = [];
    if (data.intent) parts.push(`意图：<strong>${data.intent}</strong>`);
    if (data.domain) parts.push(`领域：<strong>${data.domain}</strong>`);
    if (data.slots) {
      const slotsStr = typeof data.slots === 'object' ? JSON.stringify(data.slots) : String(data.slots);
      parts.push(`槽位：<strong>${slotsStr}</strong>`);
    }
    if (data.is_command !== undefined) parts.push(`命令：<strong>${data.is_command ? '是' : '否'}</strong>`);
    if (data.is_blocked !== undefined) parts.push(`拦截：<strong>${data.is_blocked ? '是' : '否'}</strong>`);
    if (data.command) parts.push(`命令类型：<strong>${data.command}</strong>`);
    if (data.reason) parts.push(`原因：<strong>${data.reason}</strong>`);
    if (data.response_length) parts.push(`响应长度：<strong>${data.response_length}</strong>`);
    if (data.tokens) parts.push(`Token 数：<strong>${data.tokens}</strong>`);
    return parts.join('<br>');
  },

  formatArgs(args) {
    if (!args) return '';
    return Object.entries(args)
      .map(([k, v]) => `${k}: ${typeof v === 'string' && v.length > 50 ? v.substring(0, 50) + '...' : v}`)
      .join(', ');
  },

  renderTimeline() {
    if (!this.timelineItemsEl) return;
    this.timelineItemsEl.innerHTML = this.timelineItems.map(item => {
      const statusClass = item.status === 'SUCCESS' ? 'success' : 
                         item.status === 'RUNNING' ? 'running' : 
                         item.status === 'BLOCKED' ? 'blocked' : 'failed';
      return `
        <div class="timeline-item">
          <div class="timeline-dot ${statusClass}">${item.status === 'SUCCESS' ? '✓' : item.status === 'RUNNING' ? '⟳' : '✗'}</div>
          <div class="timeline-content">
            <div class="timeline-header">
              <div class="timeline-step-name">${item.stepName}</div>
              <div class="timeline-duration">${item.duration}ms</div>
            </div>
            <div class="timeline-detail">${item.detail || '无详细信息'}</div>
          </div>
        </div>
      `;
    }).join('');
  },

  renderToolCalls() {
    if (!this.toolCallsListEl) return;
    if (this.toolCalls.length === 0) {
      this.toolCallsSection.style.display = 'none';
      return;
    }
    this.toolCallsSection.style.display = 'block';
    this.toolCallsListEl.innerHTML = this.toolCalls.map(tc => {
      const statusClass = tc.status === 'SUCCESS' ? 'success' : 'failed';
      return `
        <div class="tool-call-card ${statusClass}">
          <div class="tool-name">${tc.toolName}</div>
          <div class="tool-params">${tc.args || '无参数'}</div>
          <div class="tool-result">${tc.result || '无结果'}</div>
          <div class="tool-duration">耗时：${tc.durationMs}ms</div>
        </div>
      `;
    }).join('');
  },

  updateMetrics() {
    if (!this.startTime) return;
    const totalDuration = Date.now() - this.startTime;
    this.metricDurationEl.textContent = totalDuration + 'ms';
    
    const totalTokens = this.timelineItems
      .filter(item => item.stepName === '模型响应')
      .reduce((sum, item) => {
        const match = item.detail.match(/Token 数：<strong>(\d+)<\/strong>/);
        return sum + (match ? parseInt(match[1]) : 0);
      }, 0);
    this.metricTokensEl.textContent = totalTokens;
    
    this.metricToolCallsEl.textContent = this.toolCalls.length + '次';
  }
};

// 初始化
DebugPanel.init();

// 全局函数
function toggleDebug() {
  DebugPanel.toggle();
}
