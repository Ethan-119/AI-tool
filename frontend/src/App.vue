<template>
  <div class="app-shell">
    <div class="main-area">
      <div class="canvas-panel">
        <canvas ref="canvasRef" class="draw-canvas"></canvas>
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
import { ref, nextTick } from 'vue'
import VoiceButton from './components/VoiceButton.vue'

const canvasRef = ref(null)
const statusText = ref('')
const logs = ref([])

function handleAudioReady(wavBlob) {
  const sizeKB = (wavBlob.size / 1024).toFixed(1)
  logs.value.unshift(`录音完成 ${sizeKB}KB ${wavBlob.type}`)
  statusText.value = `已录制 ${sizeKB}KB，等待后端接口...`


  // [临时测试] 下载 WAV 到浏览器默认下载目录，用于人工验证录音效果
  // 正式上线前删除此段 —— 生产环境 WAV Blob 只走内存直接 POST，不落盘
  //
  const url = URL.createObjectURL(wavBlob)
  const a = document.createElement('a')
  a.href = url
  a.download = `recording-${Date.now()}.wav`
  a.click()
  URL.revokeObjectURL(url)
  //

  // TODO: POST /api/draw/voice → SSE 流式接收绘图结果
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
.draw-canvas {
  width: 90%; height: 90%;
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 4px 24px rgba(0,0,0,0.5);
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
