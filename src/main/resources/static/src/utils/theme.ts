import { ref } from 'vue'

const isDark = ref(false)

const saved = localStorage.getItem('h-sm-theme')
if (saved === 'dark') {
  isDark.value = true
  document.documentElement.classList.add('dark')
}

export function useDark() {
  const applyTheme = (dark: boolean, x?: number, y?: number) => {
    const toggle = () => {
      if (dark) {
        document.documentElement.classList.add('dark')
        localStorage.setItem('h-sm-theme', 'dark')
      } else {
        document.documentElement.classList.remove('dark')
        localStorage.setItem('h-sm-theme', 'light')
      }
    }

    if (document.startViewTransition) {
      const html = document.documentElement
      if (x !== undefined && y !== undefined) {
        html.style.setProperty('--tx-x', x + 'px')
        html.style.setProperty('--tx-y', y + 'px')
      }
      // 标记过渡方向: expand(暗→浅) / shrink(浅→暗)
      html.classList.remove('tx-expand', 'tx-shrink')
      html.classList.add(dark ? 'tx-shrink' : 'tx-expand')
      document.startViewTransition(() => toggle())
    } else {
      toggle()
    }
  }

  return { isDark, applyTheme }
}
