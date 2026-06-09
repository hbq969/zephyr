/// <reference types="vite/client" />
// env.d.ts 文件是 Vite 项目中用于定义全局类型声明的 TypeScript 文件。
// 它帮助开发者向 TypeScript提供全局的类型提示，特别是在使用一些特定于
// Vite 的功能时（如 import.meta.env）
// console.log("API URL:", import.meta.env.VITE_API_URL);

interface ImportMetaEnv {
  // 请求base路径
  VITE_API_URL: string
  // 是否debug模式
  VITE_DEBUG_MODE: boolean
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}