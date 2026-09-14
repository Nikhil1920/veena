import js from "@eslint/js";
import tseslint from "@typescript-eslint/eslint-plugin";
import tsParser from "@typescript-eslint/parser";
import astro from "eslint-plugin-astro";
import react from "eslint-plugin-react";
import reactHooks from "eslint-plugin-react-hooks";

export default [
	{
		ignores: ["dist/**", "node_modules/**", ".astro/**", "public/runtime/**"],
	},
	js.configs.recommended,
	{
		files: ["scripts/*.{js,mjs}", "tests/**/*.mjs", "public/audio/*.js"],
		languageOptions: {
			globals: Object.fromEntries(
				[
					"console",
					"process",
					"URL",
					"Response",
					"Request",
					"Headers",
					"fetch",
					"self",
					"caches",
					"__PRECACHE__",
					"AudioWorkletProcessor",
					"registerProcessor",
				].map((name) => [name, "readonly"])
			),
		},
	},
	tseslint.configs["flat/eslint-recommended"],
	...tseslint.configs["flat/recommended"],
	...astro.configs["flat/recommended"],
	{
		files: ["**/*.astro"],
		languageOptions: {
			parser: astro.parser,
			parserOptions: {
				parser: tsParser,
				extraFileExtensions: [".astro"],
			},
		},
	},
	{
		files: ["**/*.{js,jsx,ts,tsx}"],
		...react.configs.flat.recommended,
	},
	{
		files: ["**/*.{js,jsx,ts,tsx}"],
		...reactHooks.configs.flat.recommended,
	},
	{
		rules: {
			"react/react-in-jsx-scope": "off",
			"react/prop-types": "off",
			"@typescript-eslint/no-unused-vars": [
				"warn",
				{
					argsIgnorePattern: "^_",
					varsIgnorePattern: "^_",
				},
			],
		},
		settings: {
			react: {
				version: "detect",
			},
		},
	},
];
