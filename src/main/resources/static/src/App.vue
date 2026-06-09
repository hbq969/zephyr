<template>
  <div>
    <router-view/>
  </div>
</template>

<script lang="ts" setup>
</script>

<style>
/* ========================================
   zephyr AI Agent — DESIGN.md warm cream/coral theme
   Color palette: primary #cc785c (coral), canvas #faf9f5 (cream), dark #181715 (navy)
   Typography: serif display Georgia/Times New Roman, body Inter
   ======================================== */

:root {
  --sidebar-width: 280px;
}

* { margin: 0; padding: 0; box-sizing: border-box; }

html {
  scrollbar-width: none;
}
html::-webkit-scrollbar { width: 1px; height: 1px; }
html::-webkit-scrollbar-track { background: transparent; }
html::-webkit-scrollbar-thumb { background: rgba(0,0,0,0.40); }
html::-webkit-scrollbar-corner { background: transparent; }

body {
  font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
  background: #faf9f5;
  color: #3d3d3a;
  -webkit-font-smoothing: antialiased;
  overflow-y: auto;
}

/* ========================================
   Element Plus theme override — DESIGN.md colors
   ======================================== */
:root {
  --el-color-primary: #cc785c;
  --el-color-primary-rgb: 204, 120, 92;
  --el-color-primary-light-3: #d4947d;
  --el-color-primary-light-5: #ddb09f;
  --el-color-primary-light-7: #e8ccc1;
  --el-color-primary-light-8: #eee0d8;
  --el-color-primary-light-9: #f5efe9;
  --el-color-primary-dark-2: #a9583e;
  --el-color-white: #ffffff;
  --el-color-black: #000000;
  --el-color-success: #5db872;
  --el-color-success-light-3: #7dc994;
  --el-color-success-light-5: #9edab0;
  --el-color-success-light-7: #bee8cb;
  --el-color-success-light-8: #ceefd8;
  --el-color-success-light-9: #def5e5;
  --el-color-success-dark-2: #4a935b;
  --el-color-warning: #d4a017;
  --el-color-danger: #c64545;
  --el-color-danger-light-3: #d46d6d;
  --el-color-danger-light-5: #e29494;
  --el-color-danger-light-7: #eeb8b8;
  --el-color-danger-light-8: #f3cccc;
  --el-color-danger-light-9: #f8dfdf;
  --el-color-danger-dark-2: #9e3737;
  --el-color-error: #c64545;
  --el-color-error-light-3: #d46d6d;
  --el-color-error-light-5: #e29494;
  --el-color-error-light-7: #eeb8b8;
  --el-color-error-light-8: #f3cccc;
  --el-color-error-light-9: #f8dfdf;
  --el-color-error-dark-2: #9e3737;
  --el-color-info: #6c6a64;
  --el-color-info-light-3: #8e8b82;
  --el-color-info-light-5: #a09d96;
  --el-color-info-light-7: #c4c1ba;
  --el-color-info-light-8: #d4d2cc;
  --el-color-info-light-9: #e4e2de;
  --el-color-info-dark-2: #565450;
  --el-text-color-primary: #141413;
  --el-text-color-regular: #3d3d3a;
  --el-text-color-secondary: #6c6a64;
  --el-text-color-placeholder: #8e8b82;
  --el-text-color-disabled: #c4c1ba;
  --el-bg-color: #faf9f5;
  --el-bg-color-page: #faf9f5;
  --el-bg-color-overlay: #faf9f5;
  --el-border-color: #e6dfd8;
  --el-border-color-light: #ebe6df;
  --el-border-color-lighter: #f5f0e8;
  --el-border-color-extra-light: #faf9f5;
  --el-border-color-dark: #d4cec6;
  --el-border-color-darker: #b4aca2;
  --el-fill-color: #efe9de;
  --el-fill-color-light: #f5f0e8;
  --el-fill-color-lighter: #faf9f5;
  --el-fill-color-extra-light: #faf9f5;
  --el-fill-color-dark: #e8e0d2;
  --el-fill-color-darker: #d4cec6;
  --el-fill-color-blank: #faf9f5;
  --el-box-shadow: 0 12px 32px 4px rgba(0,0,0,0.04), 0 8px 20px rgba(0,0,0,0.08);
  --el-box-shadow-light: 0 0 12px rgba(0,0,0,0.12);
  --el-box-shadow-lighter: 0 0 6px rgba(0,0,0,0.12);
  --el-border-radius-base: 8px;
  --el-disabled-bg-color: var(--el-fill-color-light);
  --el-disabled-text-color: var(--el-text-color-disabled);
  --el-disabled-border-color: var(--el-border-color-lighter);
  --el-overlay-color: rgba(0, 0, 0, 0.8);
  --el-overlay-color-light: rgba(0, 0, 0, 0.7);
  --el-overlay-color-lighter: rgba(0, 0, 0, 0.5);
  --el-mask-color: rgba(255, 255, 255, 0.9);
  --el-mask-color-extra-light: rgba(255, 255, 255, 0.3);
}

/* ========================================
   Dark mode
   ======================================== */
html.dark body {
  background: #181715;
  color: #a09d96;
}
html.dark *::-webkit-scrollbar-thumb {
  background: rgba(255,255,255,0.20);
}

html.dark {
  --el-color-primary: #cc785c;
  --el-color-primary-rgb: 204, 120, 92;
  --el-color-primary-light-3: #d4947d;
  --el-color-primary-light-5: #ddb09f;
  --el-color-primary-light-7: #e8ccc1;
  --el-color-primary-light-8: #eee0d8;
  --el-color-primary-light-9: #3a2a22;
  --el-color-primary-dark-2: #a9583e;
  --el-color-white: #a09d96;
  --el-color-black: #0d0c0b;
  --el-color-success: #5db872;
  --el-color-danger: #c64545;
  --el-color-warning: #d4a017;
  --el-color-error: #c64545;
  --el-color-info: #8e8b82;
  --el-color-info-light-3: #a09d96;
  --el-color-info-light-5: #6c6a62;
  --el-color-info-light-7: #565450;
  --el-color-info-light-8: #4a4844;
  --el-color-info-light-9: #2a2824;
  --el-color-info-dark-2: #a09d96;
  --el-text-color-primary: #faf9f5;
  --el-text-color-regular: #a09d96;
  --el-text-color-secondary: #8e8b82;
  --el-text-color-placeholder: #6c6a62;
  --el-text-color-disabled: #4a4844;
  --el-bg-color: #181715;
  --el-bg-color-page: #181715;
  --el-bg-color-overlay: #181715;
  --el-border-color: #2a2824;
  --el-border-color-light: #2a2824;
  --el-border-color-lighter: #1f1e1b;
  --el-border-color-extra-light: #181715;
  --el-border-color-dark: #3a3834;
  --el-border-color-darker: #4a4844;
  --el-fill-color: #252320;
  --el-fill-color-light: #1f1e1b;
  --el-fill-color-lighter: #181715;
  --el-fill-color-extra-light: #181715;
  --el-fill-color-dark: #2a2824;
  --el-fill-color-darker: #3a3834;
  --el-fill-color-blank: #181715;
  --el-box-shadow: 0 12px 32px 4px rgba(0,0,0,0.24), 0 8px 20px rgba(0,0,0,0.36);
  --el-box-shadow-light: 0 0 12px rgba(0,0,0,0.36);
  --el-disabled-bg-color: var(--el-fill-color-light);
  --el-disabled-text-color: var(--el-text-color-disabled);
  --el-disabled-border-color: var(--el-border-color-lighter);
  --el-overlay-color: rgba(0, 0, 0, 0.8);
  --el-overlay-color-light: rgba(0, 0, 0, 0.7);
  --el-overlay-color-lighter: rgba(0, 0, 0, 0.5);
  --el-mask-color: rgba(0, 0, 0, 0.8);
  --el-mask-color-extra-light: rgba(0, 0, 0, 0.3);
}

/* ========================================
   Element Plus dark component overrides (non-scoped)
   ======================================== */
html.dark .el-button--default {
  --el-button-bg-color: var(--el-fill-color-light);
  --el-button-border-color: var(--el-border-color);
  --el-button-text-color: var(--el-text-color-primary);
}
html.dark .el-button.is-circle {
  background-color: var(--el-fill-color-light) !important;
  border-color: var(--el-border-color) !important;
}
html.dark .el-input__wrapper,
html.dark .el-textarea__inner {
  background-color: var(--el-fill-color-light);
  box-shadow: 0 0 0 1px var(--el-border-color) inset;
}
html.dark .el-input__inner,
html.dark .el-textarea__inner { color: var(--el-text-color-primary); }
html.dark .el-select-dropdown { background: var(--el-bg-color-overlay); }
html.dark .el-select-dropdown__item { color: var(--el-text-color-primary); }
html.dark .el-select-dropdown__item.hover,
html.dark .el-select-dropdown__item:hover { background: var(--el-fill-color-light); }
html.dark .el-select-dropdown__item.selected {
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}
html.dark .el-popper.is-light {
  background: var(--el-bg-color) !important;
  border-color: var(--el-border-color) !important;
  color: var(--el-text-color-primary) !important;
}
html.dark .el-popper.is-light .el-popper__arrow::before {
  background: var(--el-bg-color) !important;
  border-color: var(--el-border-color) !important;
}
html.dark .el-dropdown-menu { background: var(--el-bg-color); }
html.dark .el-dropdown-menu__item { color: var(--el-text-color-primary); }
html.dark .el-dropdown-menu__item:hover { background: var(--el-fill-color-light); }
html.dark .el-popover {
  --el-popover-bg-color: var(--el-bg-color);
  --el-popover-border-color: var(--el-border-color);
}
html.dark .el-dialog { --el-dialog-bg-color: var(--el-bg-color); }
html.dark .el-dialog__title { color: var(--el-text-color-primary); }
html.dark .el-table .el-icon { color: var(--el-color-white) !important; }
html.dark .el-tag--default {
  --el-tag-bg-color: var(--el-fill-color-light);
  --el-tag-border-color: var(--el-border-color);
  --el-tag-text-color: var(--el-text-color-primary);
}
html.dark .el-switch__label { color: var(--el-text-color-primary); }
html.dark .el-checkbox__label { color: var(--el-text-color-primary); }
html.dark .el-radio__label { color: var(--el-text-color-primary); }
</style>
