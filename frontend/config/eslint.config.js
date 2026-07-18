import typescriptParser from "@typescript-eslint/parser";
import zodPlugin from "eslint-plugin-zod";

const ZOD_SOURCE_FILES = ["**/*.{js,jsx,ts,tsx}"];
const ZOD_TEST_FILES = ["**/*.{test,spec}.{js,jsx,ts,tsx}", "src/test/**/*.{js,jsx,ts,tsx}"];

export default [
  {
    ignores: [
      "node_modules/**",
      "dist/**",
      "build/**",
      "coverage/**",
      "public/**",
      "**/*.svelte",
      "**/*.d.ts",
    ],
  },
  {
    files: ZOD_SOURCE_FILES,
    languageOptions: {
      ecmaVersion: "latest",
      parser: typescriptParser,
      sourceType: "module",
    },
    plugins: {
      zod: zodPlugin,
    },
    rules: {
      "zod/no-any-schema": "error",
      "zod/no-optional-and-default-together": "error",
      "zod/no-throw-in-refine": "error",
      "zod/no-empty-custom-schema": "error",
      "zod/no-number-schema-with-int": "error",
      "zod/consistent-import-source": ["warn", { sources: ["zod/v4"] }],
      "zod/require-error-message": "warn",
      "zod/prefer-enum-over-literal-union": "warn",
      "zod/require-schema-suffix": "warn",
      "zod/prefer-meta": "warn",
      "zod/array-style": "warn",
      "zod/consistent-import": ["warn", { syntax: "named" }],
    },
  },
  {
    files: ZOD_TEST_FILES,
    rules: {
      "zod/require-schema-suffix": "off",
      "zod/require-error-message": "off",
    },
  },
];
