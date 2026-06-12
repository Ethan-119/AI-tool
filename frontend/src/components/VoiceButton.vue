<template>
  <div class="voice-button-area">
    <button
      class="voice-btn"
      :class="{ recording: isRecording }"
      :disabled="!isSupported"
      @mousedown.prevent="handlePress"
      @mouseup.prevent="handleRelease"
      @mouseleave.prevent="handleRelease"
      @touchstart.prevent="handlePress"
      @touchend.prevent="handleRelease"
    >
      <span v-if="!isSupported" class="icon">&#9888;</span>
      <span v-else-if="isRecording" class="icon pulse">&#127908;</span>
      <span v-else class="icon">&#127897;</span>
    </button>

    <div v-if="isRecording" class="recording-info">
      <div class="volume-bar">
        <div class="volume-fill" :style="{ width: volumeLevel + '%' }"></div>
      </div>
      <span class="duration">{{ duration }}s</span>
    </div>

    <p v-if="!isSupported" class="hint error">当前浏览器不支持录音，请使用 Chrome 或 Edge</p>
    <p v-else-if="!isRecording" class="hint">按住说话，松开发送</p>
  </div>
</template>

<script setup>
import { useAudioRecorder } from '../composables/useAudioRecorder.js'

const emit = defineEmits(['audioReady'])
const { isRecording, volumeLevel, duration, isSupported, startRecording, stopRecording } = useAudioRecorder()

async function handlePress() {
  if (isRecording.value) return
  try {
    await startRecording()
  } catch {
    // 麦克风权限被拒
  }
}

async function handleRelease() {
  if (!isRecording.value) return
  const wavBlob = await stopRecording()
  if (wavBlob.size > 0) {
    emit('audioReady', wavBlob)
  }
}
</script>

<style scoped>
.voice-button-area {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
}
.voice-btn {
  width: 80px; height: 80px;
  border-radius: 50%;
  border: 3px solid #4a4a6a;
  background: #16213e;
  cursor: pointer;
  display: flex; align-items: center; justify-content: center;
  transition: all 0.2s;
}
.voice-btn:hover { border-color: #7b7baa; }
.voice-btn.recording {
  border-color: #e94560;
  background: #2a1a2e;
  box-shadow: 0 0 24px rgba(233,69,96,0.4);
}
.voice-btn:disabled { opacity: 0.4; cursor: not-allowed; }
.icon { font-size: 32px; user-select: none; }
.pulse { animation: pulse 0.8s infinite; }
@keyframes pulse {
  0%,100% { opacity: 1; }
  50% { opacity: 0.4; }
}
.recording-info {
  display: flex; align-items: center; gap: 10px;
}
.volume-bar {
  width: 120px; height: 6px;
  background: #2a2a4a; border-radius: 3px; overflow: hidden;
}
.volume-fill {
  height: 100%;
  background: linear-gradient(90deg, #4ade80, #e94560);
  transition: width 0.05s;
}
.duration {
  font-size: 13px; color: #aaa; min-width: 42px;
}
.hint {
  font-size: 13px; color: #666;
}
.hint.error { color: #e94560; }
</style>
