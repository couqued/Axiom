import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import https from 'https'

function sendSlack(webhookUrl, text) {
  if (!webhookUrl) return
  const body = JSON.stringify({ text })
  const url = new URL(webhookUrl)
  const req = https.request({
    hostname: url.hostname,
    path: url.pathname,
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) },
  })
  req.on('error', () => {})
  req.write(body)
  req.end()
}

function slackLifecyclePlugin(webhookUrl) {
  return {
    name: 'slack-lifecycle',
    configureServer(server) {
      server.httpServer?.once('listening', () => {
        const now = new Date().toLocaleTimeString('ko-KR', { hour12: false })
        sendSlack(webhookUrl, `🟢 *frontend (Vite)* 시작  (${now})`)
      })

      const shutdown = () => {
        const now = new Date().toLocaleTimeString('ko-KR', { hour12: false })
        sendSlack(webhookUrl, `🔴 *frontend (Vite)* 종료  (${now})`)
      }
      process.once('SIGINT', shutdown)
      process.once('SIGTERM', shutdown)
    },
  }
}

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  return {
    plugins: [react(), slackLifecyclePlugin(env.SLACK_WEBHOOK)],
  }
})
