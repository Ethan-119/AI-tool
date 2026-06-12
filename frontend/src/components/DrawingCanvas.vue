<template>
  <div class="canvas-wrapper" ref="wrapperRef">
    <canvas ref="canvasRef" :width="800" :height="600" class="draw-canvas"></canvas>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'

const canvasRef = ref(null)
const wrapperRef = ref(null)
const elements = ref([])

let ctx = null

onMounted(() => {
  ctx = canvasRef.value.getContext('2d')
  resizeCanvas()
  window.addEventListener('resize', resizeCanvas)
})

onUnmounted(() => {
  window.removeEventListener('resize', resizeCanvas)
})

function resizeCanvas() {
  const w = wrapperRef.value?.clientWidth || 800
  const h = wrapperRef.value?.clientHeight || 600
  canvasRef.value.style.width = w + 'px'
  canvasRef.value.style.height = h + 'px'
}

// ---- 绘图入口 ----

function drawCommand(op) {
  if (op.type === 'undo') {
    elements.value.pop()
  } else if (op.type === 'clear') {
    elements.value = []
  } else {
    elements.value.push({ ...op, id: Date.now() })
  }
  redrawAll()
}

function drawImage(imgData) {
  if (!imgData.imageUrl) return
  const img = new Image()
  img.crossOrigin = 'anonymous'
  img.onload = () => {
    elements.value.push({ type: 'image', id: Date.now(),
      x: imgData.x || 0, y: imgData.y || 0,
      width: imgData.width || 512, height: imgData.height || 512,
      img })
    redrawAll()
  }
  img.src = imgData.imageUrl
}

function redrawAll() {
  if (!ctx) return
  ctx.clearRect(0, 0, 800, 600)
  ctx.imageSmoothingEnabled = true

  for (const el of elements.value) {
    switch (el.type) {
      case 'circle':   drawCircle(el); break
      case 'rect':     drawRect(el); break
      case 'line':     drawLine(el); break
      case 'triangle': drawTriangle(el); break
      case 'ellipse':  drawEllipse(el); break
      case 'image':    drawElImage(el); break
    }
  }
}

// ---- 形状绘制 ----

function drawCircle(el) {
  const r = el.width / 2
  fillAndStroke(el, () => {
    ctx.beginPath()
    ctx.arc(el.x, el.y, Math.abs(r), 0, Math.PI * 2)
  })
}

function drawRect(el) {
  fillAndStroke(el, () => {
    ctx.beginPath()
    ctx.rect(el.x, el.y, el.width, el.height)
  })
}

function drawLine(el) {
  ctx.beginPath()
  ctx.moveTo(el.x, el.y)
  ctx.lineTo(el.width, el.height)
  ctx.strokeStyle = el.strokeColor || el.color || '#000'
  ctx.lineWidth = el.lineWidth || 2
  ctx.stroke()
}

function drawTriangle(el) {
  const cx = el.x, cy = el.y, w = el.width, h = el.height
  fillAndStroke(el, () => {
    ctx.beginPath()
    ctx.moveTo(cx, cy)
    ctx.lineTo(cx - w / 2, cy + h)
    ctx.lineTo(cx + w / 2, cy + h)
    ctx.closePath()
  })
}

function drawEllipse(el) {
  fillAndStroke(el, () => {
    ctx.beginPath()
    ctx.ellipse(el.x, el.y, Math.abs(el.width / 2), Math.abs(el.height / 2), 0, 0, Math.PI * 2)
  })
}

function drawElImage(el) {
  if (!el.img) return
  ctx.drawImage(el.img, el.x, el.y, el.width, el.height)
}

// ---- 通用 fill + stroke ----

function fillAndStroke(el, drawPath) {
  drawPath()
  if (el.fillColor) {
    ctx.fillStyle = el.fillColor
    ctx.fill()
  }
  if (el.strokeColor) {
    ctx.strokeStyle = el.strokeColor
    ctx.lineWidth = el.lineWidth || 2
    ctx.stroke()
  }
  if (!el.fillColor && !el.strokeColor) {
    // 无填充无描边 → 默认黑色填充
    ctx.fillStyle = '#000'
    ctx.fill()
  }
}

defineExpose({ drawCommand, drawImage })
</script>

<style scoped>
.canvas-wrapper {
  width: 100%; height: 100%;
  display: flex; align-items: center; justify-content: center;
}
.draw-canvas {
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 4px 24px rgba(0,0,0,0.5);
  max-width: 100%; max-height: 100%;
  object-fit: contain;
}
</style>
