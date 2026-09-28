// gradflow-vue@0.1.3 把未编译的 .ts 源码一并发布了，其内部使用 Nuxt 的 "@/" 别名，
// 而它的 index.d.ts 又用无扩展名的相对路径 re-export——vue-tsc 会优先命中 .ts 源码并报
// "Cannot find module '@/types/gradient'"。这里用最小声明接管该模块类型（只用 <GradFlow>）。
import type { DefineComponent } from "vue";

export declare const GradFlow: DefineComponent<Record<string, unknown>>;
