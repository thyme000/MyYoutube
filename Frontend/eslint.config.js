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
