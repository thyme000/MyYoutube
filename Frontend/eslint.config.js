import js from "@eslint/js";
import pluginVue from "eslint-plugin-vue";
import globals from "globals";

export default [
  {
    ignores: ["node_modules/**", "nginx-1.29.6/**", "dist/**", ".vite/**"],
  },
  js.configs.recommended,
  ...pluginVue.configs["flat/essential"],
  {
    files: ["src/**/*.{js,vue}"],
    languageOptions: {
      ecmaVersion: "latest",
      sourceType: "module",
      globals: { ...globals.browser },
    },
    rules: {
      "no-unused-vars": ["warn", { args: "none" }],
      "no-empty": "warn",
    },
  },
  {
    // 历史遗留：万行单体在拆分完成前不作为 lint 阻塞项
    files: ["src/App.vue"],
    rules: {
      "no-unused-vars": "off",
      "no-empty": "off",
      "vue/no-use-v-if-with-v-for": "warn",
    },
  },
  {
    // LRC 歌词解析刻意用控制字符区间识别二进制内容
    files: ["src/utils/lyricsParser.js"],
    rules: {
      "no-control-regex": "off",
    },
  },
];
