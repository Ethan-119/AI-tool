<template>
  <div class="app-shell">
    <div class="main-area">
      <div class="canvas-panel">
        <DrawingCanvas ref="drawCanvas" />
      </div>
      <div class="log-panel">
        <div v-if="statusText" class="status">{{ statusText }}</div>
        <div v-for="(log, i) in logs" :key="i" class="log-item">{{ log }}</div>
      </div>
    </div>
    <div class="bottom-bar">
      <VoiceButton @audio-ready="handleAudioReady" />
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import VoiceButton from './components/VoiceButton.vue'
import DrawingCanvas from './components/DrawingCanvas.vue'

const drawCanvas = ref(null)
const statusText = ref('')
const logs = ref([])

function handleAudioReady(wavBlob) {
  sendToBackend(wavBlob)
}

async function sendToBackend(wavBlob) {
  const sizeKB = (wavBlob.size / 1024).toFixed(1)
  logs.value.unshift(`发送中 ${sizeKB}KB...`)
  statusText.value = '正在识别语音...'

  const form = new FormData()
  form.append('audio', wavBlob, 'recording.wav')

  try {
    const res = await fetch('/api/draw/voice', { method: 'POST', body: form })
    if (!res.ok) { throw new Error('HTTP ' + res.status) }

    // 手动解析 SSE 流（POST 不能用 EventSource）
    const reader = res.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })

      // 按双换行分割 SSE 事件
      const parts = buffer.split('\n\n')
      buffer = parts.pop()  // 最后一个可能不完整，留到下次

      for (const raw of parts) {
        const event = parseSSE(raw)
        if (!event) continue
        switch (event.name) {
          case 'text':
            statusText.value = `识别: ${event.data.text}`
            logs.value.unshift(`📝 ${event.data.text}`)
            break
          case 'command':
            logs.value.unshift(`🎨 ${event.data.type}`)
            drawCommand(event.data)
            break
          case 'image':
            logs.value.unshift(`🖼 图像生成完成`)
            drawImage(event.data)
            break
          case 'progress':
            statusText.value = event.data.message || '生成中...'
            break
          case 'error':
            statusText.value = event.data.message || '出错了'
            logs.value.unshift(`❌ ${event.data.message}`)
            break
          case 'done':
            statusText.value = `完成 (第${event.data.step}步)`
            break
        }
      }
    }
  } catch (e) {
    statusText.value = '请求失败'
    logs.value.unshift(`❌ ${e.message}`)
  }
}

function parseSSE(raw) {
  const lines = raw.split('\n')
  let name = '', data = ''
  for (const line of lines) {
    if (line.startsWith('event:')) name = line.slice(6).trim()
    else if (line.startsWith('data:')) data = line.slice(5).trim()
  }
  if (!data) return null
  try { return { name: name || 'message', data: JSON.parse(data) } }
  catch { return null }
}

function drawCommand(op) {
  drawCanvas.value?.drawCommand(op)
}

function drawImage(imgData) {
  drawCanvas.value?.drawImage(imgData)
}
</script>

<style scoped>
.app-shell {
  width: 100vw; height: 100vh;
  display: flex; flex-direction: column;
}
.bottom-bar {
  height: 80px;
  display: flex; align-items: center; justify-content: center;
  background: #16213e; border-top: 1px solid #2a2a4a;
  padding-bottom: env(safe-area-inset-bottom, 0);
}
.main-area {
  flex: 1; display: flex; overflow: hidden;
}
.canvas-panel {
  flex: 1; display: flex; align-items: center; justify-content: center;
  background: #1a1a2e;
}
.log-panel {
  width: 260px;
  background: #12122a; border-left: 1px solid #2a2a4a;
  padding: 16px; overflow-y: auto;
}
.status {
  padding: 8px 12px; border-radius: 6px;
  background: #1a3a2a; color: #4ade80;
  font-size: 13px; margin-bottom: 12px;
}
.log-item {
  font-size: 12px; color: #888;
  padding: 4px 0;
  border-bottom: 1px solid #1a1a3a;
}
</style>
