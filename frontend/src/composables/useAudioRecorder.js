import { ref } from 'vue'

/**
 * 录音 + WebM→WAV 转换
 * 按住录音，松手输出 WAV Blob
 */

const WAV_SAMPLE_RATE = 16000  // DashScope Paraformer 推荐采样率

export function useAudioRecorder() {
  let stream = null
  let mediaRecorder = null
  let audioContext = null
  let analyser = null
  let volumeTimer = 0
  let recordedChunks = []

  const isRecording = ref(false)
  const volumeLevel = ref(0)
  const duration = ref(0)
  const isSupported = ref(!!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia && window.MediaRecorder))

  let durationTimer = 0

  async function startRecording() {
    recordedChunks = []
    duration.value = 0

    stream = await navigator.mediaDevices.getUserMedia({
      audio: { sampleRate: WAV_SAMPLE_RATE, channelCount: 1 }
    })

    // 录音
    const mimeType = MediaRecorder.isTypeSupported('audio/webm;codecs=opus')
      ? 'audio/webm;codecs=opus'
      : 'audio/webm'
    mediaRecorder = new MediaRecorder(stream, { mimeType })
    mediaRecorder.ondataavailable = (e) => { if (e.data.size > 0) recordedChunks.push(e.data) }
    mediaRecorder.start(100) // 每100ms一片，保证松手后快速完成

    // 音量分析
    audioContext = new AudioContext({ sampleRate: WAV_SAMPLE_RATE })
    const source = audioContext.createMediaStreamSource(stream)
    analyser = audioContext.createAnalyser()
    analyser.fftSize = 256
    source.connect(analyser)
    const dataArray = new Uint8Array(analyser.frequencyBinCount)
    function tick() {
      if (!isRecording.value) return
      analyser.getByteFrequencyData(dataArray)
      volumeLevel.value = Math.round(dataArray.reduce((a, b) => a + b, 0) / dataArray.length / 255 * 100)
      volumeTimer = requestAnimationFrame(tick)
    }
    volumeTimer = requestAnimationFrame(tick)

    // 计时
    const start = Date.now()
    durationTimer = setInterval(() => { duration.value = ((Date.now() - start) / 1000).toFixed(1) }, 100)

    isRecording.value = true
  }

  async function stopRecording() {
    return new Promise((resolve, reject) => {
      mediaRecorder.onstop = async () => {
        isRecording.value = false

        // 先捕获数据再清理，cleanup 会置 null
        const chunks = [...recordedChunks]
        const mime = mediaRecorder.mimeType
        cleanup()

        try {
          const webmBlob = new Blob(chunks, { type: mime })
          const wavBlob = await webmToWav(webmBlob)
          resolve(wavBlob)
        } catch (e) {
          console.warn('WAV 转换失败，回退到 WebM:', e)
          resolve(new Blob(chunks, { type: mime }))
        }
      }
      mediaRecorder.requestData()
      mediaRecorder.stop()
    })
  }

  function cleanup() {
    clearInterval(durationTimer)
    cancelAnimationFrame(volumeTimer)
    if (audioContext) { audioContext.close(); audioContext = null }
    if (stream) { stream.getTracks().forEach(t => t.stop()); stream = null }
    analyser = null
    mediaRecorder = null
    volumeLevel.value = 0
  }

  // ---- WebM → WAV 转换 ----
  async function webmToWav(webmBlob) {
    const arrayBuffer = await webmBlob.arrayBuffer()
    const offlineCtx = new OfflineAudioContext(1, 1, WAV_SAMPLE_RATE)
    const audioBuffer = await offlineCtx.decodeAudioData(arrayBuffer)

    // 重采样到目标采样率
    const resampled = await resample(audioBuffer, WAV_SAMPLE_RATE)
    return pcmToWav(resampled)
  }

  async function resample(audioBuffer, targetRate) {
    const originalRate = audioBuffer.sampleRate
    if (originalRate === targetRate) return audioBuffer

    const offlineCtx = new OfflineAudioContext(1, audioBuffer.duration * targetRate, targetRate)
    const source = offlineCtx.createBufferSource()
    source.buffer = audioBuffer
    source.connect(offlineCtx.destination)
    source.start(0)
    return offlineCtx.startRendering()
  }

  function pcmToWav(audioBuffer) {
    const numChannels = audioBuffer.numberOfChannels
    const sampleRate = audioBuffer.sampleRate
    const bitsPerSample = 16
    const pcmData = audioBuffer.getChannelData(0)
    const byteRate = sampleRate * numChannels * bitsPerSample / 8
    const blockAlign = numChannels * bitsPerSample / 8
    const dataSize = pcmData.length * numChannels * bitsPerSample / 8
    const headerSize = 44
    const buffer = new ArrayBuffer(headerSize + dataSize)
    const view = new DataView(buffer)

    // RIFF header
    writeString(view, 0, 'RIFF')
    view.setUint32(4, 36 + dataSize, true)
    writeString(view, 8, 'WAVE')

    // fmt chunk
    writeString(view, 12, 'fmt ')
    view.setUint32(16, 16, true)         // chunk size
    view.setUint16(20, 1, true)          // PCM
    view.setUint16(22, numChannels, true)
    view.setUint32(24, sampleRate, true)
    view.setUint32(28, byteRate, true)
    view.setUint16(32, blockAlign, true)
    view.setUint16(34, bitsPerSample, true)

    // data chunk
    writeString(view, 36, 'data')
    view.setUint32(40, dataSize, true)

    // PCM data
    let offset = 44
    for (let i = 0; i < pcmData.length; i++) {
      const sample = Math.max(-1, Math.min(1, pcmData[i]))
      const int16 = sample < 0 ? sample * 0x8000 : sample * 0x7FFF
      view.setInt16(offset, int16, true)
      offset += 2
    }

    return new Blob([buffer], { type: 'audio/wav' })
  }

  function writeString(view, offset, str) {
    for (let i = 0; i < str.length; i++) {
      view.setUint8(offset + i, str.charCodeAt(i))
    }
  }

  return { isRecording, volumeLevel, duration, isSupported, startRecording, stopRecording }
}
