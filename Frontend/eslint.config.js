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
    files: ["src/**/*.vue"],
    rules: {
      // 不整体采用 flat/recommended：它比 essential 多的 33 条里 22 条是模板排版
      // （html-indent / max-attributes-per-line / attributes-order …），
      // 而本项目不引 Prettier，用规则强推排版只会得到数千条无法自动修的噪音。
      // 这里只挑其中的正确性与 API 契约类规则。
      "vue/html-end-tags": "error",
      "vue/no-lone-template": "error",
      "vue/no-multiple-slot-args": "error",
      "vue/no-required-prop-with-default": "error",
      "vue/no-template-shadow": "error",
      "vue/no-v-html": "error",
      "vue/one-component-per-file": "error",
      "vue/require-default-prop": "error",
      "vue/require-explicit-emits": "error",
      "vue/require-prop-types": "error",
      "vue/this-in-template": "error",
    },
  },
  {
    files: ["src/**/*.{js,vue}"],
    languageOptions: {
      ecmaVersion: "latest",
      sourceType: "module",
      globals: { ...globals.browser },
    },
    rules: {
      "no-unused-vars": ["error", { args: "none" }],
      "no-empty": "error",
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
