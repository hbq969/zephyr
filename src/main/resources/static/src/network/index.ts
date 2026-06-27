import axios from 'axios'
import { ElLoading, ElMessage } from 'element-plus'
import router from '@/router'

export default (config: any) => {
  let instance = axios.create({
    baseURL: import.meta.env.VITE_API_URL,
    timeout: 600000,
    timeoutErrorMessage: '请求超时，请稍后再试'
  })

  let loadingInstance: any

  function showError(msg: string) {
    ElMessage({ message: msg, type: 'error' })
  }

  function extractErrorMessage(error: any): string {
    if (error?.response?.data?.errorMessage) return error.response.data.errorMessage
    if (error?.response?.data?.message) return error.response.data.message
    if (error?.message) return error.message
    return '请求失败，请稍后再试'
  }

  instance.interceptors.request.use(function(config: any) {
    if (config.url && !config.url.startsWith('/chat') && !config.url.startsWith('/conversations') && !config.url.startsWith('/workspace')) {
      loadingInstance = ElLoading.service({
        lock: true,
        text: '加载中...',
        fullscreen: true,
        background: 'rgba(0, 0, 0, 0.7)'
      })
    }
    return config
  }, function(error: any) {
    return Promise.reject(error)
  })

  instance.interceptors.response.use(function(response: any) {
    if (loadingInstance) loadingInstance.close()
    return response
  }, function(error: any) {
    if (loadingInstance) loadingInstance.close()
    showError(extractErrorMessage(error))
    return Promise.reject(error)
  })

  return instance(config)
}
