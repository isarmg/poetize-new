import js from '@eslint/js'
import pluginVue from 'eslint-plugin-vue'
import globals from 'globals'

export default [
  {
    ignores: [
      'dist/**',
      'src/utils/bubble.js',
      'src/utils/letter.js',
      'src/utils/sakura.js',
      'src/utils/twopeople.js',
      'src/utils/zdog.js'
    ]
  },
  js.configs.recommended,
  ...pluginVue.configs['flat/essential'],
  {
    files: ['**/*.{js,vue}'],
    languageOptions: {
      ecmaVersion: 'latest',
      sourceType: 'module',
      globals: {
        ...globals.browser,
        $: 'readonly',
        jQuery: 'readonly',
        loadlive2d: 'readonly'
      }
    },
    rules: {
      'no-console': 'off',
      'no-debugger': 'off',
      'no-empty': 'off',
      'no-unused-vars': 'warn',
      'vue/multi-word-component-names': 'off'
    }
  }
]
