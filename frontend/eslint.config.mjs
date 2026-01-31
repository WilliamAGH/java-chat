import tseslint from "@typescript-eslint/eslint-plugin";
import tsParser from "@typescript-eslint/parser";

const namingConventionRules = [
  "warn",
  { selector: "variable", modifiers: ["destructured"], format: null },
  { selector: "parameter", modifiers: ["destructured"], format: null },
  { selector: "function", format: ["camelCase", "PascalCase"] },
  {
    selector: "variable",
    modifiers: ["const"],
    format: ["camelCase", "PascalCase", "UPPER_CASE"]
  },
  { selector: "variable", format: ["camelCase", "PascalCase"] },
  { selector: "parameter", format: ["camelCase"], leadingUnderscore: "allow" },
  { selector: "typeLike", format: ["PascalCase"] }
];

export default [
  {
    ignores: [
      "node_modules/**",
      "dist/**",
      "build/**",
      "coverage/**",
      "public/**",
      "**/*.d.ts"
    ]
  },
  {
    files: ["**/*.{js,jsx,ts,tsx}"],
    languageOptions: {
      parser: tsParser,
      parserOptions: {
        ecmaVersion: 2022,
        sourceType: "module"
      }
    },
    plugins: {
      "@typescript-eslint": tseslint
    },
    rules: {
      "@typescript-eslint/naming-convention": namingConventionRules
    }
  }
];
