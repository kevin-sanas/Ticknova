import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import './styles.css'
import App from './App.vue'
import { router } from './router'
import { bindApiSession } from './api'
import { useSessionStore } from './stores/session'
const app = createApp(App)
app.use(createPinia())
const session = useSessionStore()
bindApiSession({ getToken: () => session.token, onUnauthorized: () => session.clear('登录已失效，请重新登录。') })
app.use(ElementPlus, { locale: zhCn })
app.use(router)
app.mount('#app')
